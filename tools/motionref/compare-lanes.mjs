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

async function compare(left, right) {
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
        const error = Math.abs(reference[index] - candidate[index]);
        sum += error;
        max = Math.max(max, error);
        if (error > MAX_CHANNEL_ERROR) aboveLimit++;
    }
    const mean = sum / expectedBytes;
    return {requestUs: left.requestUs, editorUs: left.editorUs,
        width: left.width, height: left.height, max, mean, aboveLimit,
        equivalent: max <= MAX_CHANNEL_ERROR && mean <= MEAN_CHANNEL_ERROR};
}

try {
    const [leftPath, rightPath, ...options] = process.argv.slice(2);
    const expectDifferent = options[0] === '--expect-different';
    if (!leftPath || !rightPath || options.length > 1 || (options.length === 1 && !expectDifferent)) {
        throw new Error('Usage: node compare-lanes.mjs reference.json candidate.json [--expect-different]');
    }
    const [left, right] = await Promise.all([manifest(leftPath), manifest(rightPath)]);
    if (left.length !== right.length) throw new Error('Sample count mismatch');
    const samples = [];
    for (let i = 0; i < left.length; i++) samples.push(await compare(left[i], right[i]));
    const passed = expectDifferent ? samples.every(sample => !sample.equivalent)
            : samples.every(sample => sample.equivalent);
    console.log(JSON.stringify({expectDifferent, passed, samples}, null, 2));
    if (!passed) process.exitCode = 1;
} catch (error) {
    console.error(error.message);
    process.exitCode = 2;
}
