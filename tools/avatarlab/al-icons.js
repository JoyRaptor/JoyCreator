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
    'depth-front': { d: 'M7 8V5a2 2 0 012-2h4a2 2 0 012 2v3M15 16v3a2 2 0 01-2 2H9a2 2 0 01-2-2v-3', f: 'M3.5 10h14.3a3 3 0 110 4H3.5a2 2 0 010-4z' },
    'depth-behind': { d: 'M9 3h4a2 2 0 012 2v14a2 2 0 01-2 2H9a2 2 0 01-2-2V5a2 2 0 012-2z', f: 'M5 10v4H3.5a2 2 0 010-4zM20 9a3 3 0 110 6 3 3 0 010-6z' },
    'depth-half': { d: 'M15 8V5a2 2 0 00-2-2H9a2 2 0 00-2 2v14a2 2 0 002 2h4a2 2 0 002-2v-3', f: 'M5 10v4H3.5a2 2 0 010-4zM11 10h6.8a3 3 0 110 4H11z' },
    'stand': { d: 'M12 2.5a2 2 0 100 4 2 2 0 000-4zM12 6.5v8M8 10h8M12 14.5l-3.5 7M12 14.5l3.5 7' },
    'tile-under': { d: 'M12 10h7a2 2 0 012 2v7a2 2 0 01-2 2h-7a2 2 0 01-2-2v-7a2 2 0 012-2z', f: 'M5 3h7a2 2 0 012 2v3H8v6H5a2 2 0 01-2-2V5a2 2 0 012-2z' },
    'tile-over': { d: 'M7.5 14H5a2 2 0 01-2-2V5a2 2 0 012-2h7a2 2 0 012 2v2.5', f: 'M12 10h7a2 2 0 012 2v7a2 2 0 01-2 2h-7a2 2 0 01-2-2v-7a2 2 0 012-2z' },
    'tile-top': { d: 'M10 17H9a2 2 0 01-2-2V9a2 2 0 012-2h6a2 2 0 012 2v1M5 12H4a2 2 0 01-2-2V4a2 2 0 012-2h6a2 2 0 012 2v1', f: 'M14 12h6a2 2 0 012 2v6a2 2 0 01-2 2h-6a2 2 0 01-2-2v-6a2 2 0 012-2z' },
    'tile-bottom': { d: 'M14 12h6a2 2 0 012 2v6a2 2 0 01-2 2h-6a2 2 0 01-2-2v-6a2 2 0 012-2zM9.5 17H9a2 2 0 01-2-2V9a2 2 0 012-2h6a2 2 0 012 2v.5', f: 'M4 2h6a2 2 0 012 2v1H5v7H4a2 2 0 01-2-2V4a2 2 0 012-2z' },
    'hand-foot': { d: 'M2 21h20M6 11.5l9-1.5 2.3-1.7M19 5a2 2 0 100 4 2 2 0 000-4zM6 11.5L5 20M6 11.5l3 8.5', a: 'M15 10l.5 10H18' },
    'air-jump': { d: 'M12 2a2 2 0 100 4 2 2 0 000-4zM12 6v6M7.5 6.5L12 9l4.5-2.5M5 21.5h14', a: 'M12 12l-4 1.5 1.5 4M12 12l4 1.5-1.5 4' },
    'air-hang': { d: 'M12 2a2 2 0 100 4 2 2 0 000-4zM12 6v6M8.5 12V8.5h7V12M5 21.5h14', a: 'M12 12l-1.5 6M12 12l1.5 6' },
    'mouth-hinge': { d: 'M5 9h15', f: 'M5 6.8a2.2 2.2 0 110 4.4 2.2 2.2 0 010-4.4z', a: 'M5 9l13.5 8.5' },
    'mouth-squash': { d: 'M12 7.5c4.4 0 8 2 8 4.5s-3.6 4.5-8 4.5-8-2-8-4.5 3.6-4.5 8-4.5zM7 12h10', a: 'M10 2.5l2 2 2-2M10 21.5l2-2 2 2' },
    'mouth-swap': { d: 'M6.5 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7zM16 12h5.5', a: 'M13.5 6l-2 12' },
    'mouth-open': { d: 'M12 7c3.3 0 6 2.2 6 5s-2.7 5-6 5-6-2.2-6-5 2.7-5 6-5z' },
    'mouth-shut': { d: 'M5 11.5c3 2 11 2 14 0' },
    'wrap-stay': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16z', f: 'M12 9a3 3 0 110 6 3 3 0 010-6z' },
    'wrap-on': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16zM12 4c-3 4-3 12 0 16', f: 'M6.2 9c.7 0 1.2 1.3 1.2 3s-.5 3-1.2 3S5 13.7 5 12s.5-3 1.2-3z' },
    'wrap-out': { d: 'M12 4a8 8 0 100 16 8 8 0 000-16z', f: 'M15.6 4.8l6.7-2.6-3 6.2z' },
    'face-left': { d: 'M13.5 5a7 7 0 100 14 7 7 0 000-14zM10.5 10v.5', a: 'M7 9.5L2.5 13h4.1' },
    'face-front': { d: 'M12 5a7 7 0 100 14 7 7 0 000-14zM9.5 10v.5M14.5 10v.5', a: 'M12 11.5v3' },
    'face-right': { d: 'M10.5 5a7 7 0 100 14 7 7 0 000-14zM13.5 10v.5', a: 'M17 9.5l4.5 3.5h-4.1' },
    'lids-cut': { d: 'M2 12c3 4.7 6.5 7 10 7s7-2.3 10-7M2 12h20M9 12a3 3 0 006 0', a: 'M5.3 8.6h.01M8.6 6.2h.01M12 5.4h.01M15.4 6.2h.01M18.7 8.6h.01' },
    'lids-drawn': { d: 'M2 12c3-4.7 6.5-7 10-7s7 2.3 10 7c-3 4.7-6.5 7-10 7s-7-2.3-10-7zM9 12a3 3 0 006 0', f: 'M2 12c3-4.7 6.5-7 10-7s7 2.3 10 7z' },
    'fit-box': { d: 'M5 3.5h14v10H5zM12 13.5v3', f: 'M5 1.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM19 1.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM5 11.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM19 11.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6z', a: 'M12 17.5a2 2 0 100 4 2 2 0 000-4z' },
    'fit-pins': { d: 'M4 7h9l7.5-3.5L18 12v8H4z', f: 'M20.5 1.3a2.2 2.2 0 110 4.4 2.2 2.2 0 010-4.4zM8 9.2a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6zM12 14.7a1.8 1.8 0 110 3.6 1.8 1.8 0 010-3.6z' },
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
    'breathe': { d: 'M12 3a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM7.5 21v-5.5a4.5 4.5 0 019 0V21', a: 'M4.5 13.5c-1.2 2.3-1.2 4.7 0 7M19.5 13.5c1.2 2.3 1.2 4.7 0 7' }
  };
  var SEG = { 'Like a hose': 'bend-hose-pins', 'In front': 'depth-front', 'Behind': 'depth-behind', 'Half and half': 'depth-half', 'Jumps': 'air-jump', 'Hangs': 'air-hang',
    'Hinge': 'mouth-hinge', 'Squash': 'mouth-squash', 'Swap only': 'mouth-swap', 'Open': 'mouth-open', 'Shut': 'mouth-shut', 'Stays put': 'wrap-stay', 'Painted on': 'wrap-on', 'Sticks out': 'wrap-out',
    'Invisible': 'lids-cut', 'Drawn': 'lids-drawn', 'Separate drawings': 'group-parts', 'One part that swaps': 'group-swap', 'Frames of a loop': 'group-loop', 'Find the parts': 'cut-find', 'Even grid': 'cut-grid' };
  var FACE = { Left: 'face-left', Front: 'face-front', Right: 'face-right' };
  var CHIP = { 'Move, turn, resize': 'fit-box', 'Move it': 'fit-box', 'Reshape with pins': 'fit-pins', 'Back one': 'tile-under', 'Forward one': 'tile-over', 'Very back': 'tile-bottom', 'Very front': 'tile-top', 'As when standing': 'stand',
    'Neutral': 'mood-neutral', 'Relaxed': 'mood-relaxed', 'Sleepy': 'mood-sleepy', 'Angry': 'mood-angry', 'Sad': 'mood-sad', 'Cheery': 'mood-cheery', 'Wary': 'mood-wary' };
  AL.picFor = function (kind, label, title) {
    if (kind === 'seg') { if (/^At the /.test(label)) return 'bend-joint-pins'; if (FACE[label]) return /drawn/.test(title || '') ? FACE[label] : null; return SEG[label] || null; }
    if (kind === 'tg') { if (/can become (feet|a foot)$/i.test(label)) return 'hand-foot'; if (/comes in front$/.test(label)) return 'depth-half'; return label === 'Breathing' ? 'breathe' : null; }
    return CHIP[label] || null;
  };
})(typeof window !== 'undefined' ? window : globalThis);
