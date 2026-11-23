/* AvatarLab: PICTURES ON CONTROLS. Small drawings that explain a choice without reading it.
 *
 * Each was approved by the owner on the examples page (11-21). d = grey lines (the rest of the character), f = a solid shape and
 * a = lines in the mark colour (the part the choice is about). A control finds its picture by its own words, so no card has
 * to name one: AL.picFor('seg' | 'chip' | 'tg', label, title).
 */
(function (root) {
  'use strict';
  var AL = root.AL;
  AL.PICS = {
    'bend-joint-pins': { d: 'M6 2.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM6 18.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM7.6 5l9.4 7-9.4 7', f: 'M17 9.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z' },
    'bend-hose-pins': { d: 'M6 2.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM6 18.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM7.8 4.4c11.5 1.2 11.5 14 0 15.2' },
    'depth-front-reach': { d: 'M8.5 2a2 2 0 100 4 2 2 0 000-4zM3 22V12a3 3 0 013-3h5a3 3 0 011.93.7', f: 'M10.8 10.6a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zM10.8 17.2a2 2 0 1 1 0 4 2 2 0 0 1 0-4zM9.85 18.43L6.94 17.71A.6 .6 0 0 1 7.23 16.54L10.15 17.27zM9.8 19.8L6.4 19.8A.6 .6 0 0 1 6.4 18.6L9.8 18.6zM10.15 21.13L7.23 21.86A.6 .6 0 0 1 6.94 20.69L9.85 19.97zM10.68 18.19L10.18 16.36A.75 .75 0 0 1 11.63 15.97L12.12 17.81z', a: 'M10.8 12.5H16c6.2 0 6.2 6.7-.5 6.7h-2.7' },
    'depth-behind-reach': { d: 'M8.5 2a2 2 0 100 4 2 2 0 000-4zM3 22V12a3 3 0 013-3h5a3 3 0 013 3v10', a: 'M16.8 12.5c5.4 0 5.4 6.7 0 6.7' },
    'depth-half': { d: 'M8.5 2a2 2 0 100 4 2 2 0 000-4zM3 22V12a3 3 0 013-3h5a3 3 0 013 3v4.4', f: 'M10.8 17.2a2 2 0 1 1 0 4 2 2 0 0 1 0-4zM9.85 18.43L6.94 17.71A.6 .6 0 0 1 7.23 16.54L10.15 17.27zM9.8 19.8L6.4 19.8A.6 .6 0 0 1 6.4 18.6L9.8 18.6zM10.15 21.13L7.23 21.86A.6 .6 0 0 1 6.94 20.69L9.85 19.97zM10.68 18.19L10.18 16.36A.75 .75 0 0 1 11.63 15.97L12.12 17.81z', a: 'M16.8 12.5c5.4 0 5.4 6.7-1.3 6.7h-2.7' },
    'depth-half-tip': { d: 'M8.5 2a2 2 0 100 4 2 2 0 000-4zM3 22V12a3 3 0 013-3h5a3 3 0 011.93.7M14 15.3V22', f: 'M10.8 10.6a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8z', a: 'M10.8 12.5H16c6.2 0 6.2 6.7.8 6.7' },
    'stand': { d: 'M12 2.5a2 2 0 100 4 2 2 0 000-4zM12 6.5v8M8 10h8M12 14.5l-3.5 7M12 14.5l3.5 7' },
    'legs-four': { d: 'M6 11h9.5l2-2.5M19 4.5a2 2 0 100 4 2 2 0 000-4zM6 11l-2 9M6 11l2.5 9M15.5 11l-2.5 9M15.5 11l2 9M6 11L2.5 7.5' },
    'tile-under': { d: 'M12 10h7a2 2 0 012 2v7a2 2 0 01-2 2h-7a2 2 0 01-2-2v-7a2 2 0 012-2z', f: 'M5 3h7a2 2 0 012 2v3H8v6H5a2 2 0 01-2-2V5a2 2 0 012-2z' },
    'tile-over': { d: 'M7.5 14H5a2 2 0 01-2-2V5a2 2 0 012-2h7a2 2 0 012 2v2.5', f: 'M12 10h7a2 2 0 012 2v7a2 2 0 01-2 2h-7a2 2 0 01-2-2v-7a2 2 0 012-2z' },
    'tile-top': { d: 'M10 17H9a2 2 0 01-2-2V9a2 2 0 012-2h6a2 2 0 012 2v1M5 12H4a2 2 0 01-2-2V4a2 2 0 012-2h6a2 2 0 012 2v1', f: 'M14 12h6a2 2 0 012 2v6a2 2 0 01-2 2h-6a2 2 0 01-2-2v-6a2 2 0 012-2z' },
    'tile-bottom': { d: 'M14 12h6a2 2 0 012 2v6a2 2 0 01-2 2h-6a2 2 0 01-2-2v-6a2 2 0 012-2zM9.5 17H9a2 2 0 01-2-2V9a2 2 0 012-2h6a2 2 0 012 2v.5', f: 'M4 2h6a2 2 0 012 2v1H5v7H4a2 2 0 01-2-2V4a2 2 0 012-2z' },
    'hand-foot': { d: 'M2 21h20M6 11.5l9-1.5 2.3-1.7M19 5a2 2 0 100 4 2 2 0 000-4zM6 11.5L5 20M6 11.5l3 8.5', a: 'M15 10l.5 10H18' },
    'air-jump': { d: 'M12 2a2 2 0 100 4 2 2 0 000-4zM12 6v6M7.5 6.5L12 9l4.5-2.5M5 21.5h14', a: 'M12 12l-4 1.5 1.5 4M12 12l4 1.5-1.5 4' },
    'air-hang': { d: 'M12 2a2 2 0 100 4 2 2 0 000-4zM12 6v6M8.5 12V8.5h7V12M5 21.5h14', a: 'M12 12l-1.5 6M12 12l1.5 6' },
    'ground-line': { d: 'M12 2.5a2 2 0 100 4 2 2 0 000-4zM12 6.5V13M8 9.5h8M12 13l-3 5.5M12 13l3 5.5', a: 'M3 20.5h18' },
    'ground-any': { d: 'M12 2.5a2 2 0 100 4 2 2 0 000-4zM12 6.5V13M8 9.5h8M12 13l-3 5.5M12 13l3 5.5', a: 'M9 14.5H7l-4.5 7h19l-4.5-7h-2' },
    'gait-steps-tracks': { d: 'M3.5 9h3M8.5 15h3M13.5 9h3M18.5 15h3' },
    'gait-hops-tracks': { d: 'M3.5 9H7M3.5 15H7M17 9h3.5M17 15h3.5', a: 'M9 12c1.8-2.5 4.2-2.5 6 0' },
    'mouth-hinge': { d: 'M5 9h15', f: 'M5 6.8a2.2 2.2 0 110 4.4 2.2 2.2 0 010-4.4z', a: 'M5 9l13.5 8.5' },
    'mouth-squash': { d: 'M12 7.5c4.4 0 8 2 8 4.5s-3.6 4.5-8 4.5-8-2-8-4.5 3.6-4.5 8-4.5zM7 12h10', a: 'M10 2.5l2 2 2-2M10 21.5l2-2 2 2' },
    'mouth-swap': { d: 'M6.5 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7zM16 12h5.5', a: 'M13.5 6l-2 12' },
    'mouth-open': { d: 'M12 7c3.3 0 6 2.2 6 5s-2.7 5-6 5-6-2.2-6-5 2.7-5 6-5z' },
    'mouth-shut': { d: 'M5 11.5c3 2 11 2 14 0' },
    'wrap-stay': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16z', f: 'M12 9a3 3 0 110 6 3 3 0 010-6z' },
    'wrap-on': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16zM12 4c-3 4-3 12 0 16', f: 'M6.2 9c.7 0 1.2 1.3 1.2 3s-.5 3-1.2 3S5 13.7 5 12s.5-3 1.2-3z' },
    'wrap-out': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16z', f: 'M15.6 4.8l6.7-2.6-3 6.2z' },
    'swing-bendy': { d: 'M12 2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6z', a: 'M12 5.6c0 4 5 4.5 4 8.5s-6 3.5-6 7.5' },
    'swing-stiff': { d: 'M12 2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6z', a: 'M12.7 5.4l4.3 9.8M18 15a2.5 2.5 0 100 5 2.5 2.5 0 000-5z' },
    'swing-hang': { d: 'M3 9l18-5', a: 'M12 6.5v10M12 16.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z' },
    'face-left': { d: 'M13.5 5a7 7 0 100 14 7 7 0 000-14zM10.5 10v.5', a: 'M7 9.5L2.5 13h4.1' },
    'face-front': { d: 'M12 5a7 7 0 100 14 7 7 0 000-14zM9.5 10v.5M14.5 10v.5', a: 'M12 11.5v3' },
    'face-right': { d: 'M10.5 5a7 7 0 100 14 7 7 0 000-14zM13.5 10v.5', a: 'M17 9.5l4.5 3.5h-4.1' },
    'turn-smooth': { d: 'M5 16.5a7 7 0 0114 0M16 13.5l3 3 3-3' },
    'turn-snap': { d: 'M4 8h16M16.5 4.5L20 8l-3.5 3.5M20 16H4M7.5 12.5L4 16l3.5 3.5' },
    'turn-card': { d: 'M12 2v2.5M12 19.5V22', a: 'M7 7.5l10-3v15l-10-3z' },
    'lids-cut': { d: 'M2 12c3 4.7 6.5 7 10 7s7-2.3 10-7M2 12h20M9 12a3 3 0 006 0', a: 'M5.3 8.6h.01M8.6 6.2h.01M12 5.4h.01M15.4 6.2h.01M18.7 8.6h.01' },
    'lids-drawn': { d: 'M2 12c3-4.7 6.5-7 10-7s7 2.3 10 7c-3 4.7-6.5 7-10 7s-7-2.3-10-7zM9 12a3 3 0 006 0', f: 'M2 12c3-4.7 6.5-7 10-7s7 2.3 10 7z' },
    'fit-box': { d: 'M5 3.5h14v10H5zM12 13.5v3', f: 'M5 1.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM19 1.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM5 11.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM19 11.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6z', a: 'M12 17.5a2 2 0 100 4 2 2 0 000-4z' },
    'fit-pins': { d: 'M4 7h9l7.5-3.5L18 12v8H4z', f: 'M20.5 1.3a2.2 2.2 0 110 4.4 2.2 2.2 0 010-4.4zM8 9.2a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM12 14.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6z' },
    'foot-bar': { d: 'M8 3v10l-2.5 2.5M13 3v10c3 .5 5.5 1.5 6 3.5', f: 'M5 18a2 2 0 110 4 2 2 0 010-4zM19 18a2 2 0 110 4 2 2 0 010-4z', a: 'M5 20h14' },
    'flip-bend': { d: 'M8.2 6h.01M6.5 8h.01M4.8 10h.01M3 12h.01M4.8 14h.01M6.5 16h.01M8.2 18h.01M7 12h6M11 10l2 2-2 2', a: 'M10 4l7 8-7 8' },
    'group-parts': { d: 'M2.5 8.5h5v7h-5zM9.5 8.5h5v7h-5zM16.5 8.5h5v7h-5z' },
    'group-swap': { d: 'M4 9h10v11H4zM7.5 7V5.5h10v11H16M11 3.5V2h10v11h-1.5' },
    'group-loop': { d: 'M2.5 3.5h5v7h-5zM9.5 3.5h5v7h-5zM16.5 3.5h5v7h-5z', a: 'M19 14v1.5a3 3 0 01-3 3H5M8 15.5l-3 3 3 3' },
    'cut-find': { d: 'M7.5 3.5a4.5 4.5 0 100 9 4.5 4.5 0 000-9zM18 4a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM8 16h10a2.5 2.5 0 010 5H8a2.5 2.5 0 010-5z' },
    'cut-grid': { d: 'M3 4h18v16H3zM9 4v16M15 4v16M3 12h18' },
    'mood-neutral': { d: 'M7 9.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7zM17 9.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7zM7 13h.01M17 13h.01' },
    'mood-relaxed': { d: 'M3.5 12.5h7a3.5 3.5 0 01-7 0zM13.5 12.5h7a3.5 3.5 0 01-7 0z' },
    'mood-sleepy': { d: 'M3.5 14h7c-.8 1.7-2 2.5-3.5 2.5s-2.7-.8-3.5-2.5zM13.5 14h7c-.8 1.7-2 2.5-3.5 2.5s-2.7-.8-3.5-2.5z' },
    'mood-angry': { d: 'M3.9 12.9l6.4 2.8a3.5 3.5 0 11-6.4-2.8zM20.1 12.9l-6.4 2.8a3.5 3.5 0 106.4-2.8zM3 6.5l7.5 3.3M21 6.5l-7.5 3.3' },
    'mood-sad': { d: 'M10.1 12.9l-6.4 2.8a3.5 3.5 0 106.4-2.8zM13.9 12.9l6.4 2.8a3.5 3.5 0 11-6.4-2.8zM3 9.8l7.5-3.3M21 9.8l-7.5-3.3' },
    'mood-cheery': { d: 'M3.5 14.5a3.5 3.5 0 017 0M13.5 14.5a3.5 3.5 0 017 0M4 8.5c2-1.3 4-1.3 6 0M14 8.5c2-1.3 4-1.3 6 0' },
    'mood-wary': { d: 'M4.5 13.5h5a1.5 1.5 0 010 3h-5a1.5 1.5 0 010-3zM14.5 13.5h5a1.5 1.5 0 010 3h-5a1.5 1.5 0 010-3zM3.5 8l7 1.2M20.5 8l-7 1.2' },
    'bounce': { d: 'M3 15h18', a: 'M3 5c4 0 5 14 9 14 2.5 0 3-6 5-6 1.5 0 2 2 4 2' },
    'mirror-make': { d: 'M12 3v18M9 7l-5 5 5 5z', a: 'M15 7l5 5-5 5z' },
    'breathe': { d: 'M12 3a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM7.5 21v-5.5a4.5 4.5 0 019 0V21', a: 'M4.5 13.5c-1.2 2.3-1.2 4.7 0 7M19.5 13.5c1.2 2.3 1.2 4.7 0 7' }
  };
  var SEG = { 'Like a hose': 'bend-hose-pins', 'In front': 'depth-front-reach', 'Behind': 'depth-behind-reach', 'Half and half': 'depth-half', 'Jumps': 'air-jump', 'Hangs': 'air-hang',
    'On a ground line': 'ground-line', 'Walking anywhere': 'ground-any', 'Steps': 'gait-steps-tracks', 'Hops': 'gait-hops-tracks',
    'Hinge': 'mouth-hinge', 'Squash': 'mouth-squash', 'Swap only': 'mouth-swap', 'Open': 'mouth-open', 'Shut': 'mouth-shut', 'Stays put': 'wrap-stay', 'Painted on': 'wrap-on', 'Sticks out': 'wrap-out',
    'Bendy': 'swing-bendy', 'Stiff': 'swing-stiff', 'Smooth': 'turn-smooth', 'Instant': 'turn-snap', 'A card': 'turn-card',
    'Invisible': 'lids-cut', 'Drawn': 'lids-drawn', 'Separate drawings': 'group-parts', 'One part that swaps': 'group-swap', 'Frames of a loop': 'group-loop', 'Find the parts': 'cut-find', 'Even grid': 'cut-grid' };
  var FACE = { Left: 'face-left', Front: 'face-front', Right: 'face-right' };
  var CHIP = { 'Move, turn, resize': 'fit-box', 'Move it': 'fit-box', 'Reshape with pins': 'fit-pins', 'Back one': 'tile-under', 'Forward one': 'tile-over', 'Very back': 'tile-bottom', 'Very front': 'tile-top', 'As when standing': 'stand',
    'On two legs': 'stand', 'On four legs': 'legs-four', 'Ground': 'ground-line', 'Bendy': 'swing-bendy', 'Stiff': 'swing-stiff', 'Settles with a bounce': 'bounce', 'Mirror the other side': 'mirror-make', 'Breathes': 'breathe',
    'Neutral': 'mood-neutral', 'Relaxed': 'mood-relaxed', 'Sleepy': 'mood-sleepy', 'Angry': 'mood-angry', 'Sad': 'mood-sad', 'Cheery': 'mood-cheery', 'Wary': 'mood-wary' };
  AL.picFor = function (kind, label, title) {
    if (kind === 'seg') {
      if (/^At the /.test(label)) return 'bend-joint-pins';
      if (title === 'Which end is behind') return label === 'Shoulder' || label === 'Hip' ? 'depth-half' : 'depth-half-tip';      // (the end named is the one behind)
      if (FACE[label]) return /drawn/.test(title || '') ? FACE[label] : null;
      return SEG[label] || null;
    }
    if (kind === 'tg') { if (/can become (feet|a foot)$/i.test(label)) return 'hand-foot'; if (/comes in front$/.test(label)) return 'depth-half'; if (/^Foot bar/.test(label)) return 'foot-bar'; return label === 'Breathing' ? 'breathe' : label === 'Hangs down' ? 'swing-hang' : null; }
    if (/^Flip (elbow|knee)$/.test(label)) return 'flip-bend';
    return CHIP[label] || null;
  };
})(typeof window !== 'undefined' ? window : globalThis);
