#!/usr/bin/env node
import {execFile} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {appendFile, mkdir, readFile, writeFile} from 'node:fs/promises';
import {join, resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {promisify} from 'node:util';

const run = promisify(execFile);
const APP = 'com.fadcam.beta';
const ACTIVITY = APP + '/com.fadcam.ui.faditor.export.ExportDebugActivity';
const REQUESTS = [66000, 500000, 900000];

function sha256(path) {
    return new Promise((resolveHash, reject) => {
        const stream = createReadStream(path);
        const hash = createHash('sha256');
        stream.on('data', chunk => hash.update(chunk));
        stream.on('end', () => resolveHash(hash.digest('hex')));
        stream.on('error', reject);
    });
}

function caseArguments(test, requests) {
    if (!/^[a-z0-9-]{1,60}$/.test(test.label)
            || typeof test.ordered !== 'boolean' || typeof test.above !== 'boolean') {
        throw new Error('Invalid lane fixture case');
    }
    const args = ['--ez', 'motion_lane_fixture', 'true',
        '--ez', 'motion_lane_compositor', String(test.ordered),
        '--ez', 'motion_lane_adjustment_above', String(test.above)];
    if (!test.measureOnly) args.push('--ela', 'motion_lane_sample_us', requests.join(','));
    if (test.variant !== undefined) {
        if (!/^[a-z0-9-]{1,60}$/.test(test.variant)) throw new Error('Invalid fixture variant');
        args.push('--es', 'motion_lane_variant', test.variant);
    }
    for (const [field, extra] of [
        ['pip', 'motion_lane_include_pip'],
        ['title', 'motion_lane_include_title'],
        ['sprite', 'motion_lane_include_sprite'],
        ['adjustment', 'motion_lane_enable_adjustment'],
    ]) {
        if (test[field] === undefined) continue;
        if (typeof test[field] !== 'boolean') throw new Error('Invalid fixture option ' + field);
        args.push('--ez', extra, String(test[field]));
    }
    return args;
}

async function main() {
    const [phoneScript, adbExe, serial, testApk, recoveryApk, casePath, outPath, ...rest] =
            process.argv.slice(2);
    if (!outPath || rest.length || !/^[a-zA-Z0-9.:-]+$/.test(serial)) {
        throw new Error('Usage: node capture-lanes.mjs phone.ps1 adb.exe serial test.apk recovery.apk cases.json output');
    }

    const cases = JSON.parse(await readFile(casePath, 'utf8'));
    if (!Array.isArray(cases) || !cases.length) throw new Error('No requested fixtures');
    const work = cases.map(test => {
        const requests = test.times ?? REQUESTS;
        if (!Array.isArray(requests) || requests.length !== 3
                || requests.some((time, index) => !Number.isSafeInteger(time) || time < 0
                        || (index > 0 && time <= requests[index - 1]))) {
            throw new Error('Expected three increasing editor timestamps');
        }
        return {test, args: caseArguments(test, requests), requests};
    });
    if (new Set(cases.map(c => c.label)).size !== cases.length) {
        throw new Error('Duplicate fixture receipt label');
    }

    const holder = 'codex-c4b-lane-' + randomUUID();
    const quote = arg => "'" + String(arg).replaceAll("'", "''") + "'";
    const call = (...args) => run('powershell.exe', ['-NoProfile', '-Command',
        "Remove-Item Env:PHONE_SLOT -ErrorAction SilentlyContinue; $env:PHONE=" + quote(serial)
        + '; $env:JB_PHONE_HOLDER=' + quote(holder)
        + '; & ' + quote(resolve(phoneScript)) + ' ' + args.map(quote).join(' ')],
        {timeout: 65000, maxBuffer: 1048576});
    const adb = (...args) => run(resolve(adbExe), ['-s', serial, ...args],
        {timeout: 30000, maxBuffer: 20 * 1024 * 1024, encoding: 'buffer'});
    const read = async (...args) => (await adb(...args)).stdout.toString('utf8');

    const currentHash = async () => {
        const path = (await read('shell', 'pm', 'path', APP)).trim();
        if (!/^package:\/data\/app\/[^\r\n]+\/base\.apk$/.test(path)) {
            throw new Error('Expected one installed base APK on the sandbox');
        }
        return (await read('shell', 'sha256sum', path.slice(8))).trim().split(/\s+/)[0];
    };
    const install = async file => {
        const answer = await call('adb', 'install', '-r', resolve(file));
        if (!answer.stdout.includes('Success')) throw new Error('APK install was not confirmed');
    };

    const property = (await read('shell', 'getprop', 'ro.product.model')).trim();
    if (property.replace(/[-_]/g, '') !== 'SMN960U') {
        throw new Error('Expected the Snapdragon Note 9 sandbox');
    }
    const restoreHash = await sha256(recoveryApk);
    const output = resolve(outPath);
    await mkdir(output, {recursive: true});
    const receipt = {holder, sourceApkHash: await sha256(testApk), recoveryApkHash: restoreHash,
        runs: [], caseFailures: [], restored: false};
    const receiptPath = join(output, 'receipt.json');
    const saveReceipt = options => writeFile(receiptPath, JSON.stringify(receipt, null, 2), options);
    await saveReceipt({flag: 'wx'});
    let claimed = false;
    let installAttempted = false;
    let nextThermalCheck = 0;
    const sampleThermal = async force => {
        const now = performance.now();
        if (!force && now < nextThermalCheck) return;
        nextThermalCheck = now + 30000;
        const [battery, thermal] = await Promise.all([
            read('shell', 'dumpsys', 'battery'), read('shell', 'dumpsys', 'thermalservice'),
        ]);
        const temperature = battery.match(/^\s*temperature:\s*(-?\d+)\s*$/m);
        const status = thermal.match(/^\s*Thermal Status:\s*([0-6])\s*$/mi);
        await appendFile(join(output, 'thermal.jsonl'), JSON.stringify({
            time: new Date().toISOString(), batteryC: temperature ? Number(temperature[1]) / 10 : null,
            status: status ? Number(status[1]) : null,
        }) + '\n');
    };
    try {
        await call('claim', holder, 'STU-7 disposable lane-order framebuffer fixtures');
        claimed = true;
        if (restoreHash !== await currentHash()) throw new Error('Recovery APK differs from installed APK');
        await sampleThermal(true);
        await call('key', 'KEYCODE_HOME');
        installAttempted = true;
        await install(testApk);
        if (receipt.sourceApkHash !== await currentHash()) throw new Error('Installed test APK hash mismatch');
        for (const {test, args, requests} of work) {
            const folder = join(output, test.label);
            await mkdir(folder, {recursive: true});
            await call('shell-write', 'am', 'force-stop', APP);
            const scratch = requests.map(requested => 'cache/lane_frame_' + requested + '.rgba');
            await call('shell-write', 'run-as', APP, 'rm', '-f', ...scratch);
            const since = (await read('shell', "date '+%m-%d %H:%M:%S.000'")).trim();
            const marker = 'begin-' + randomUUID();
            await call('shell-write', 'log', '-t', 'LaneFixture', marker);
            await call('start', ACTIVITY, ...args);
            let logs = '';
            let samples = [];
            let finished = false;
            const attempts = test.measureOnly ? 145 : 35;
            for (let i = 0; i < attempts; i++) {
                await delay(650);
                await sampleThermal(false);
                const buffer = await read('logcat', '-d', '-T', since, '-v', 'epoch', '-s', 'LaneFixture',
                    'ExportDebug', 'ExportManager', 'ExportService', 'LaneReadback',
                    'LaneComposite', 'ProjectStorage');
                const startOfCase = buffer.indexOf('LaneFixture: ' + marker);
                if (startOfCase < 0) continue;
                logs = buffer.slice(startOfCase);
                samples = [...logs.matchAll(/frame=(lane_frame_(\d+)\.rgba) editorUs=(\d+) size=(\d+)x(\d+)/g)];
                finished = /ExportService: Export (completed|error|failed)/i.test(logs);
                if (finished && (test.measureOnly || samples.length === 3
                        || /ExportService: Export (error|failed)/i.test(logs))) break;
            }
            await writeFile(join(folder, 'device.log'), logs);
            const ordered = logs.includes('LANE_EXPORT');
            const expectedOrdered = test.expectedOrdered ?? test.ordered;
            if (test.measureOnly) {
                const start = logs.match(/^\s*(\d+\.\d+).*ExportManager: Export started/m);
                const done = logs.match(/^\s*(\d+\.\d+).*ExportManager: TRACE_COMMIT/m);
                const duration = logs.match(/EXPORT_ITEM total=(\d+)ms/);
                if (!finished || ordered !== expectedOrdered || !start || !done || +duration?.[1] !== 30000) {
                    receipt.caseFailures.push({label: test.label, ordered, expectedOrdered, finished,
                        durationMs: duration ? +duration[1] : null});
                    continue;
                }
                const stats = {label: test.label, ordered, timelineDurationMs: +duration[1],
                    encodeMs: Math.round((+done[1] - +start[1]) * 1000),
                    glSample: logs.split('\n').find(line => line.includes('GL_SAMPLE'))?.trim()};
                await writeFile(join(folder, 'performance.json'), JSON.stringify(stats, null, 2));
                receipt.runs.push(test.label);
                await saveReceipt();
                continue;
            }
            const capturedRequests = new Set(samples.map(match => Number(match[2])));
            if (ordered !== expectedOrdered || samples.length !== 3 || !finished
                    || !requests.every(request => capturedRequests.has(request))) {
                receipt.caseFailures.push({label: test.label, ordered, expectedOrdered,
                    samples: samples.length, finished});
                continue;
            }
            const manifest = {label: test.label, ordered, samples: []};
            for (const [, file, requestUs, editorUs, width, height] of samples) {
                const raw = (await adb('exec-out', 'run-as', APP, 'cat', 'cache/' + file)).stdout;
                if (raw.length !== Number(width) * Number(height) * 4) {
                    throw new Error('Incomplete raw frame ' + file);
                }
                await writeFile(join(folder, file), raw);
                manifest.samples.push({file, requestUs: +requestUs, editorUs: +editorUs,
                    width: +width, height: +height});
            }
            await call('shell-write', 'run-as', APP, 'rm', '-f', ...scratch);
            await writeFile(join(folder, 'manifest.json'), JSON.stringify(manifest, null, 2));
            receipt.runs.push(test.label);
            await saveReceipt();
        }
        await sampleThermal(true);
    } catch (error) {
        receipt.error = error.message;
    } finally {
        try {
            if (installAttempted) {
                await call('holds');
                await install(recoveryApk);
                if (restoreHash !== await currentHash()) throw new Error('Restored APK hash mismatch');
                receipt.restored = true;
            }
            if (claimed) receipt.lock = (await call('release', holder)).stdout.trim();
        } catch (error) {
            receipt.restoreError = error.message;
        }
        await saveReceipt();
    }
    console.log(JSON.stringify(receipt, null, 2));
    if (receipt.error || receipt.restoreError || receipt.caseFailures.length) process.exitCode = 1;
}

main().catch(error => {
    console.error(error.message);
    process.exitCode = 2;
});
