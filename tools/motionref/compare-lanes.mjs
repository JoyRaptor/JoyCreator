#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';

const MAX_CHANNEL_ERROR = 1;
const MEAN_CHANNEL_ERROR = 0.1;

async function manifest(path) {
    const record = JSON.parse(await readFile(path, 'utf8'));
    if (!Array.isArray(record.samples) || record.samples.length === 0) {
        throw new Error('Missing samples in ' + path);
    }
    const directory = dirname(resolve(path));
    return record.samples.map(sample => ({...sample, file: resolve(directory, sample.file)}))
            .sort((left, right) => left.requestUs - right.requestUs);
}

async function compare(left, right, invertReference) {
    for (const key of ['requestUs', 'editorUs', 'width', 'height']) {
        if (!Number.isSafeInteger(left[key]) || left[key] !== right[key]) {
            throw new Error('Unmatched ' + key + ' at sample ' + left.requestUs);
        }
    }
    const expectedBytes = left.width * left.height * 4;
    if (left.width <= 0 || left.height <= 0 || !Number.isSafeInteger(expectedBytes)) {
        throw new Error('Invalid framebuffer dimensions');
    }
    const [reference, candidate] = await Promise.all([readFile(left.file), readFile(right.file)]);
    if (reference.length !== expectedBytes || candidate.length !== expectedBytes) {
        throw new Error('Truncated RGBA capture at sample ' + left.requestUs);
    }
    let sum = 0;
    let max = 0;
    let aboveLimit = 0;
    for (let index = 0; index < expectedBytes; index++) {
        let expected = reference[index];
        if (invertReference) {
            if (index % 4 !== 3) expected = 255 - expected;
            else if (expected !== 255) throw new Error('CPU invert requires an opaque reference');
        }
        const error = Math.abs(expected - candidate[index]);
        sum += error;
        max = Math.max(max, error);
        if (error > MAX_CHANNEL_ERROR) aboveLimit++;
    }
    const mean = sum / expectedBytes;
    return {requestUs: left.requestUs, editorUs: left.editorUs,
        width: left.width, height: left.height, max, mean, aboveLimit,
        equivalent: max <= MAX_CHANNEL_ERROR && mean <= MEAN_CHANNEL_ERROR};
}

async function checkPair(leftPath, rightPath, options = {}) {
    const {expectDifferent = false, invertReference = false} = options;
    const [left, right] = await Promise.all([manifest(leftPath), manifest(rightPath)]);
    if (left.length !== right.length) throw new Error('Sample count mismatch');
    const samples = [];
    for (let i = 0; i < left.length; i++) samples.push(await compare(left[i], right[i], invertReference));
    const passed = expectDifferent ? samples.every(sample => !sample.equivalent)
            : samples.every(sample => sample.equivalent);
    return {expectDifferent, invertReference, passed, samples};
}

async function checkBatch(casesPath, evidenceRoot) {
    const cases = JSON.parse(await readFile(casesPath, 'utf8'));
    if (!Array.isArray(cases) || !cases.length) throw new Error('Missing fixture cases');
    const labels = new Set(cases.map(fixture => fixture.label));
    if (labels.size !== cases.length || [...labels].some(label => !/^[a-z0-9-]{1,60}$/.test(label))) {
        throw new Error('Invalid or duplicate fixture label');
    }
    const results = [];
    for (const label of labels) {
        if (!label.endsWith('-ordered')) continue;
        const name = label.slice(0, -'-ordered'.length);
        const ungraded = name + '-ungraded';
        const invertReference = labels.has(ungraded);
        const left = invertReference ? ungraded : name + '-legacy';
        if (!labels.has(left)) throw new Error('Missing reference case for ' + label);
        const data = await checkPair(resolve(evidenceRoot, left, 'manifest.json'),
                resolve(evidenceRoot, label, 'manifest.json'), {invertReference});
        results.push({name, ...data});
    }
    if (!results.length) throw new Error('No paired controls in the requested batch');
    return {passed: results.every(result => result.passed), results};
}

try {
    const [first, second, ...options] = process.argv.slice(2);
    let result;
    if (first === '--batch' && second && options.length === 1) {
        result = await checkBatch(second, options[0]);
    } else if (first && second && new Set(options).size === options.length
            && options.every(option => option === '--expect-different' || option === '--invert-reference')) {
        result = await checkPair(first, second, {
            expectDifferent: options.includes('--expect-different'),
            invertReference: options.includes('--invert-reference'),
        });
    } else {
        throw new Error('Usage: node compare-lanes.mjs reference.json candidate.json [--expect-different] '
                + '[--invert-reference] or node compare-lanes.mjs --batch cases.json evidence-directory');
    }
    console.log(JSON.stringify(result, null, 2));
    if (!result.passed) process.exitCode = 1;
} catch (error) {
    console.error(error.message);
    process.exitCode = 2;
}
