#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {join} from 'node:path';

const runs = [
    ['legacy-1', false],
    ['ordered-2', true],
    ['ordered-3', true],
    ['legacy-4', false],
];

async function readJson(path) {
    return JSON.parse(await readFile(path, 'utf8'));
}

async function compare(directory) {
    const receipt = await readJson(join(directory, 'receipt.json'));
    if (receipt.restored !== true || receipt.error || receipt.restoreError
            || receipt.caseFailures?.length !== 0 || !/^[a-f0-9]{64}$/.test(receipt.sourceApkHash)
            || !Array.isArray(receipt.runs) || receipt.runs.length !== runs.length
            || !runs.every(([label], index) => receipt.runs[index] === label)) {
        throw new Error('Expected a complete four-run capture with the original APK restored');
    }

    const samples = await Promise.all(runs.map(async ([label, ordered]) => {
        const folder = join(directory, label);
        const stats = await readJson(join(folder, 'performance.json'));
        if (stats.label !== label || stats.ordered !== ordered || stats.timelineDurationMs !== 30000
                || !Number.isSafeInteger(stats.encodeMs) || stats.encodeMs <= 0) {
            throw new Error('Invalid 30-second timing for ' + label);
        }
        const log = await readFile(join(folder, 'device.log'), 'utf8');
        const glSamples = log.split(/\r?\n/).map(line => line.trim())
                .filter(line => /\bGL_SAMPLE n=[1-9]\d*\s+\S+=\d+%/.test(line));
        if (!glSamples.length) throw new Error('Missing GL_SAMPLE phases for ' + label);
        return {label, ordered, encodeMs: stats.encodeMs, glSamples};
    }));

    const legacyMs = (samples[0].encodeMs + samples[3].encodeMs) / 2;
    const orderedMs = (samples[1].encodeMs + samples[2].encodeMs) / 2;
    return {passed: orderedMs <= legacyMs, sourceApkHash: receipt.sourceApkHash,
        legacyMs, orderedMs, deltaMs: orderedMs - legacyMs, samples};
}

try {
    const [, , directory, ...extra] = process.argv;
    if (!directory || extra.length) {
        throw new Error('Usage: node compare-lane-performance.mjs evidence-directory');
    }
    const results = await compare(directory);
    console.log(JSON.stringify(results, null, 2));
    if (!results.passed) process.exitCode = 1;
} catch (error) {
    console.error(error.message);
    process.exitCode = 2;
}
