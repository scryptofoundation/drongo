// Builds src/main/resources/erc20/templates.json: every ERC-20 the LitVM tab can deploy, as OpenZeppelin Wizard
// style sources compiled with a pinned solc. The app ships the bytecode and the exact sources, so each deployed token
// can be verified on the network's explorer. Run with build.sh, which pins and checks the compiler and OpenZeppelin.
//
//   node generate.mjs <solc binary> <openzeppelin package dir> <output json>

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { posix } from 'node:path';

const [solc, ozDir, outFile] = process.argv.slice(2);
if(!solc || !ozDir || !outFile) {
    console.error('usage: node generate.mjs <solc> <openzeppelin package dir> <output json>');
    process.exit(2);
}

const COMPILER = 'v0.8.36+commit.8a079791';
const OZ_VERSION = '5.6.1';
const OZ_PREFIX = '@openzeppelin/contracts/';
const SETTINGS = { optimizer: { enabled: true, runs: 200 }, evmVersion: 'cancun' };
const MAX_RUNTIME_SIZE = 24576; //EIP-170

const version = execFileSync(solc, ['--version']).toString();
if(!version.includes(COMPILER.substring(1))) {
    throw new Error('Expected solc ' + COMPILER + ', got ' + version);
}
const ozPackage = JSON.parse(readFileSync(posix.join(ozDir, 'package.json'), 'utf8'));
if(ozPackage.version !== OZ_VERSION) {
    throw new Error('Expected OpenZeppelin ' + OZ_VERSION + ', got ' + ozPackage.version);
}

const SUPPLIES = ['fixed', 'mintable', 'capped'];
//Letter, feature name, in the order the key and the inheritance list use them
const FEATURES = [['b', 'burnable'], ['p', 'pausable'], ['e', 'permit'], ['v', 'votes'], ['f', 'flashMint'], ['c', 'erc1363']];

function variants() {
    const list = [];
    for(const supply of SUPPLIES) {
        for(let mask = 0; mask < 1 << FEATURES.length; mask++) {
            const v = { supply };
            let letters = '';
            FEATURES.forEach(([letter, name], i) => {
                v[name] = (mask & (1 << i)) !== 0;
                if(v[name]) letters += letter;
            });
            v.key = supply + (letters ? '_' + letters : '');
            list.push(v);
        }
    }
    return list;
}

function source(v) {
    const mintable = v.supply !== 'fixed';
    const capped = v.supply === 'capped';
    const ownable = mintable || v.pausable;
    const imports = [['ERC20', 'token/ERC20/ERC20.sol']];
    const bases = ['ERC20'];
    const add = (name, path) => { imports.push([name, path]); bases.push(name); };
    if(v.burnable) add('ERC20Burnable', 'token/ERC20/extensions/ERC20Burnable.sol');
    if(capped) add('ERC20Capped', 'token/ERC20/extensions/ERC20Capped.sol');
    if(v.pausable) add('ERC20Pausable', 'token/ERC20/extensions/ERC20Pausable.sol');
    if(ownable) add('Ownable', 'access/Ownable.sol');
    if(v.erc1363) add('ERC1363', 'token/ERC20/extensions/ERC1363.sol');
    if(v.permit) add('ERC20Permit', 'token/ERC20/extensions/ERC20Permit.sol');
    if(v.votes) add('ERC20Votes', 'token/ERC20/extensions/ERC20Votes.sol');
    if(v.flashMint) add('ERC20FlashMint', 'token/ERC20/extensions/ERC20FlashMint.sol');
    if(v.votes && !v.permit) imports.push(['EIP712', 'utils/cryptography/EIP712.sol']);
    if(v.votes && v.permit) imports.push(['Nonces', 'utils/Nonces.sol']);
    imports.sort((a, b) => a[0].localeCompare(b[0]));

    const params = ['string memory name_', 'string memory symbol_', 'address recipient', 'uint256 initialSupply'];
    if(ownable) params.push('address initialOwner');
    if(capped) params.push('uint256 cap_');
    const inits = ['ERC20(name_, symbol_)'];
    if(capped) inits.push('ERC20Capped(cap_)');
    if(ownable) inits.push('Ownable(initialOwner)');
    if(v.permit) inits.push('ERC20Permit(name_)');
    if(v.votes && !v.permit) inits.push('EIP712(name_, "1")');

    const lines = [];
    lines.push('// SPDX-License-Identifier: MIT');
    lines.push('// Compatible with OpenZeppelin Contracts ^5.6.0');
    lines.push('pragma solidity ^0.8.27;');
    lines.push('');
    for(const [name, path] of imports) {
        lines.push(`import {${name}} from "${OZ_PREFIX}${path}";`);
    }
    lines.push('');
    lines.push(`contract Token is ${bases.join(', ')} {`);
    lines.push(`    constructor(${params.join(', ')})`);
    for(const init of inits) {
        lines.push(`        ${init}`);
    }
    lines.push('    {');
    lines.push('        if (initialSupply > 0) {');
    lines.push('            _mint(recipient, initialSupply);');
    lines.push('        }');
    lines.push('    }');
    if(v.pausable) {
        lines.push('', '    function pause() public onlyOwner {', '        _pause();', '    }');
        lines.push('', '    function unpause() public onlyOwner {', '        _unpause();', '    }');
    }
    if(mintable) {
        lines.push('', '    function mint(address to, uint256 amount) public onlyOwner {', '        _mint(to, amount);', '    }');
    }
    if(v.votes) {
        lines.push('', '    function clock() public view override returns (uint48) {', '        return uint48(block.timestamp);', '    }');
        lines.push('', '    // solhint-disable-next-line func-name-mixedcase', '    function CLOCK_MODE() public pure override returns (string memory) {',
            '        return "mode=timestamp";', '    }');
    }
    if(capped && v.flashMint) {
        lines.push('', '    function maxFlashLoan(address token) public view override returns (uint256) {',
            '        return token == address(this) ? cap() - totalSupply() : 0;', '    }');
    }
    const updaters = ['ERC20'];
    if(capped) updaters.push('ERC20Capped');
    if(v.pausable) updaters.push('ERC20Pausable');
    if(v.votes) updaters.push('ERC20Votes');
    const overrides = [];
    if(updaters.length > 1) {
        overrides.push(['    function _update(address from, address to, uint256 value)',
            `        internal`, `        override(${updaters.join(', ')})`, '    {', '        super._update(from, to, value);', '    }'].join('\n'));
    }
    if(v.votes && v.permit) {
        overrides.push(['    function nonces(address owner)', '        public', '        view', '        override(ERC20Permit, Nonces)',
            '        returns (uint256)', '    {', '        return super.nonces(owner);', '    }'].join('\n'));
    }
    if(overrides.length > 0) {
        lines.push('', '    // The following functions are overrides required by Solidity.');
        for(const o of overrides) {
            lines.push('', o);
        }
    }
    lines.push('}');
    return lines.join('\n') + '\n';
}

//Every source a unit imports, transitively, keyed by its source unit name as solc sees it
function closure(unitName, content, all) {
    all[unitName] = content;
    const re = /^\s*import\s+(?:\{[^}]*\}\s+from\s+)?"([^"]+)"\s*;/gm;
    let m;
    while((m = re.exec(content)) !== null) {
        let target = m[1];
        if(target.startsWith('.')) {
            target = posix.normalize(posix.join(posix.dirname(unitName), target));
        }
        if(!target.startsWith(OZ_PREFIX)) {
            throw new Error(unitName + ' imports ' + target + ' outside OpenZeppelin');
        }
        if(!(target in all)) {
            const text = readFileSync(posix.join(ozDir, target.substring(OZ_PREFIX.length)), 'utf8');
            closure(target, text, all);
        }
    }
    return all;
}

function input(sources) {
    const s = {};
    for(const name of Object.keys(sources).sort()) {
        s[name] = { content: sources[name] };
    }
    return { language: 'Solidity', sources: s, settings: SETTINGS };
}

function compile(sources) {
    const req = input(sources);
    req.settings = { ...SETTINGS, outputSelection: { 'Token.sol': { Token: ['evm.bytecode.object', 'evm.deployedBytecode.object', 'metadata'] } } };
    const out = JSON.parse(execFileSync(solc, ['--standard-json'], { input: JSON.stringify(req), maxBuffer: 256 * 1024 * 1024 }).toString());
    const errors = (out.errors || []).filter(e => e.severity === 'error');
    if(errors.length > 0) {
        throw new Error(errors.map(e => e.formattedMessage).join('\n'));
    }
    //Warnings inside OpenZeppelin's own files (future keywords used as identifiers) are theirs, not ours
    const warnings = (out.errors || []).filter(e => e.severity !== 'error' && e.sourceLocation?.file === 'Token.sol');
    return { contract: out.contracts['Token.sol'].Token, warnings };
}

const shared = {};
const result = { compiler: COMPILER, openZeppelin: OZ_VERSION, contractName: 'Token', sourceName: 'Token.sol', settings: SETTINGS, sources: shared, variants: {} };
let largest = 0;
for(const v of variants()) {
    const text = source(v);
    const sources = closure('Token.sol', text, {});
    const { contract, warnings } = compile(sources);
    for(const w of warnings) {
        console.warn(v.key + ': ' + w.message);
    }
    const metadata = JSON.parse(contract.metadata);
    const used = Object.keys(metadata.sources).sort();
    const given = Object.keys(sources).sort();
    if(JSON.stringify(used) !== JSON.stringify(given)) {
        throw new Error(v.key + ': metadata sources differ from the sources given');
    }
    const runtime = contract.evm.deployedBytecode.object.length / 2;
    if(runtime > MAX_RUNTIME_SIZE) {
        throw new Error(v.key + ': runtime code ' + runtime + ' bytes is over the EIP-170 limit');
    }
    largest = Math.max(largest, runtime);
    for(const name of used) {
        if(name !== 'Token.sol') {
            if(name in shared && shared[name] !== sources[name]) {
                throw new Error('Two contents for ' + name);
            }
            shared[name] = sources[name];
        }
    }
    result.variants[v.key] = { source: text, imports: used.filter(n => n !== 'Token.sol'), bytecode: contract.evm.bytecode.object };
}
const sortedShared = {};
for(const name of Object.keys(shared).sort()) {
    sortedShared[name] = shared[name];
}
result.sources = sortedShared;
writeFileSync(outFile, JSON.stringify(result));
console.log(`${Object.keys(result.variants).length} variants, ${Object.keys(shared).length} OpenZeppelin sources, largest runtime ${largest} bytes`);
