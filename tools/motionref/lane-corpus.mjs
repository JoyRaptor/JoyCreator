#!/usr/bin/env node
// Each request creates its own persisted Studio project and captures three real export frames.
const compatible = [
    'base', 'picture-opacity', 'picture-offset', 'picture-rotation', 'picture-flip',
    'picture-keys', 'picture-stack', 'adjust-window', 'adjust-opacity', 'adjust-mask',
    'adjust-blend', 'spine-zoom', 'clip-trim', 'caption',
].map(variant => ({label: variant, variant,
    ...(variant === 'clip-trim' ? {times: [66000, 500000, 800000]} : {}),
}));

const trueOrder = [
    'base', 'picture-opacity', 'picture-offset', 'picture-rotation', 'picture-flip',
    'picture-vertical-flip', 'picture-scale', 'picture-keys', 'picture-stack', 'picture-edge',
].map(variant => ({label: variant, variant})).concat([
    {label: 'title', title: true},
    {label: 'sprite', sprite: true},
    {label: 'title-sprite', title: true, sprite: true},
]);

const fallbacks = [
    {label: 'retime', variant: 'clip-retime', expectedOrdered: false},
    {label: 'loop', variant: 'clip-loop', expectedOrdered: false},
    {label: 'matte', variant: 'pip-matte', pip: true, expectedOrdered: false},
    {label: 'transition', variant: 'clip-transition', expectedOrdered: true},
];

const pipRepeats = [
    {label: 'pip', pip: true},
    {label: 'pip-blend', variant: 'pip-blend', pip: true},
];

const groups = {
    compatible: compatible.flatMap(fixture => [
        {...fixture, label: fixture.label + '-legacy', ordered: false, above: false},
        {...fixture, label: fixture.label + '-ordered', ordered: true, above: false},
    ]),
    'true-order': trueOrder.flatMap(fixture => [
        {...fixture, label: fixture.label + '-ungraded', ordered: true, above: true, adjustment: false},
        {...fixture, label: fixture.label + '-ordered', ordered: true, above: true},
    ]),
    fallback: fallbacks.map(fixture => ({...fixture, ordered: true, above: false})),
    performance: [false, true, true, false].map((ordered, index) => ({
        label: (ordered ? 'ordered-' : 'legacy-') + (index + 1),
        ordered, above: false, variant: 'performance', measureOnly: true,
    })),
    'pip-repeat': pipRepeats.flatMap(fixture => [
        {...fixture, label: fixture.label + '-legacy', ordered: false, above: false},
        {...fixture, label: fixture.label + '-repeat', ordered: false, above: false},
        {...fixture, label: fixture.label + '-ordered', ordered: true, above: false},
    ]),
};

const [group, offset, count, ...extra] = process.argv.slice(2);
if (!Object.hasOwn(groups, group) || extra.length || (offset !== undefined && !/^\d+$/.test(offset))
        || (count !== undefined && !/^[1-9]\d*$/.test(count))) {
    console.error('Usage: node lane-corpus.mjs compatible|true-order|fallback|pip-repeat|performance [offset count]');
    process.exitCode = 2;
} else {
    const start = Number(offset ?? 0);
    const cases = groups[group].slice(start, start + Number(count ?? groups[group].length));
    if (!cases.length) {
        console.error('No fixtures in the requested range');
        process.exitCode = 2;
    } else {
        console.log(JSON.stringify(cases, null, 2));
    }
}
