/* AvatarLab: GROUND AND WALKING. Everything about walking on one card.
 *
 * It opens from the bar under the feet on the body map. From the top: where the ground is (a pin on the picture), how the
 * character walks, then every limb that can walk with its own few settings (does it walk, how high its hand stands, its
 * place in the round of steps), then the way to the all-fours stance. Nothing about walking lives only somewhere else:
 * a limb's own card still shows that limb's settings, as a second door to the same numbers.
 * Phone: the same card; the Ground pin is the stage's existing ground handle.
 */
(function (root) {
  'use strict';
  var AL = root.AL, ground = AL.ground = {};
  function isLimb(n) { return !!(n && n.type === 'set' && n.pick && n.pick.by === 'reach' && n.pick.hand); }

  ground.card = function (app, pb) {
    var ui = app.ui, h = AL.h, ch = app.ch, st = ch.settings, can = AL.walkDefaults(ch), wk = st.walk;
    AL.cards.head(app, 'Ground and walking', can ? null : 'no legs', 'Where the ground is, how it walks, and which limbs do the walking.');
    app.coach(can ? '<b>Ground.</b> Drag the <b>Ground</b> pin to where the feet stand. Then tap <b>Try it</b> and drag the body along.' : '<b>Ground.</b> This character has no legs to walk on. It can float instead.');
    AL.cards.walkControls(app, pb, false);

    var limbs = AL.findAll(ch.root, isLimb);
    if (limbs.length) {
      pb.appendChild(ui.lab('Which limbs walk'));
      limbs.forEach(function (set) {
        var leg = set.pick.hand.charAt(0) === 'F', isRole = set.role === 'legL' || set.role === 'legR', walks = AL.walksOn(set), nm = AL.cards.nameOf(app, set);
        var kid = AL.node(ch, (set.pick.walk && set.pick.walk.kid) || set.pick.rest) || (set.kids || [])[0];
        function w() { return set.pick.walk || (set.pick.walk = leg ? {} : { lift: true }); }
        var open = h('button', { class: 'chip', title: 'Open ' + nm + ': its drawings and pins', onclick: function () { app.tab = 'parts'; if (set.role && app.zoneNode(set.role) === set) app.select(set.role); else app.select(AL.zoneOf(ch, set.id), null, set.id); } }, [kid ? AL.cards.mini(kid, 22) : null, nm]);
        var say = h('span', { class: 'note', style: 'margin:0', text: leg ? (walks ? 'walks' : 'does not walk') : (walks ? 'walks when the ground is in reach' : 'a hand only') });
        pb.appendChild(ui.row([open, say]));
        if (!leg) pb.appendChild(ui.toggle('Can become a foot', walks, function (v) { app.edit(function () { if (v) { set.pick.walk = set.pick.walk || { lift: true }; delete set.pick.walk.on; } else delete set.pick.walk; }, false, nm + (v ? ' can walk' : ' is a hand only')); }, 'When the ground comes within reach of the hand (the body tipped onto all fours, say) it plants and steps like a foot'));
        else if (!isRole) pb.appendChild(ui.toggle('This leg walks', walks, function (v) { app.edit(function () { w().on = v; }, false, 'A leg walks'); }, 'Off: it only hangs where it was drawn'));
        if (!walks) return;
        if (!leg) pb.appendChild(ui.slider('Stands this high', { min: 0, max: 60, value: Math.round(set.pick.walk && set.pick.walk.palm != null ? set.pick.walk.palm : AL.palmGuess(ch, kid)), set: function (v) { w().palm = v; }, fmt: function (v) { return v ? v + ' px up' : 'on the ground'; }, title: 'How far above the ground line the wrist pin stays when the hand is planted. 0 puts it on the line; raise it if the hand sinks into the ground.' }));
        var side = /R$/.test(set.role || '') ? 0.5 : 0, ph = set.pick.walk && set.pick.walk.phase != null ? set.pick.walk.phase : side + (leg ? 0 : 0.25);
        pb.appendChild(ui.slider('Step timing', { min: 0, max: 95, step: 5, value: Math.round(ph * 100), set: function (v) { w().phase = v / 100; }, fmt: function (v) { return v ? v + '% round' : 'first'; }, title: 'Its place in the round of steps. Feet with the same timing step together; the others take turns in order. Two legs: 0 and 50. Four: 0, 25, 50, 75.' }));
      });
    }
    var fs = AL.stance.fours(ch);
    if (fs || AL.stance.parts(ch)) {
      pb.appendChild(ui.lab('On all fours'));
      if (fs) pb.appendChild(ui.row([ui.chip('Work on the all-fours stance', { icon: 'person', cls: 'go', title: 'Puts it down on all fours and opens the stance: how far it tips, where each part sits', fn: function () { app.poseEdit = fs.id; app.tab = 'parts'; app.select('body'); } })]));
      else pb.appendChild(ui.row([ui.chip('Make an all-fours stance', { icon: 'wand', cls: 'go', title: 'Tips the body forward about the hips until the hands reach the ground', fn: function () { var made; app.edit(function () { made = AL.stance.makeFours(ch); }, true, 'Make an all-fours stance'); if (made) app.poseEdit = made.id; app.tab = 'parts'; app.select('body'); } })]));
    }
    app.pinProvider = function () {
      if (!(wk && can && wk.on !== false && wk.ground !== 'plane')) return [];
      return [{ key: 'ground', label: 'Ground', x: st.axisX == null ? ch.w / 2 : st.axisX, y: wk.groundY, node: null, color: '#a78bfa', set: function (x, y) { wk.groundY = Math.round(y); } }];
    };
  };
})(typeof window !== 'undefined' ? window : globalThis);
