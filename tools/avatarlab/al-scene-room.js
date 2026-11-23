/* AvatarLab: ONE ROOM. The four stages and the path tabs work on whatever is in hand (the owner's second design, 11-21).
 *
 * In hand is the scene, or one character in it. The rail (Set up, Arrange, Motion, Perform) is the only rail, and the path tabs say
 * what is in hand: "Meadow > Topps > Head". The scene's tab wears the director's chair (the owner, 11-22: the chair means the scene,
 * on the stage and on the path), and is lit while the scene is in hand. A character in hand in the first three stages is alone on the plain stage, exactly as
 * when it is opened by itself, and the tab with the scene's name puts the scene back in hand. Perform is always the whole scene,
 * with the character in hand. With no scene open (the empty scene) nothing here changes anything.
 * The scene in use, its stage and who is in hand are remembered through a reload.
 *
 * Phone: the same destinations with a "what is in hand" argument; the path is the breadcrumb row the character already has.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, scene = AL.scene, stages = AL.stages;

  /* the rail: on the scene, its own stages; on a character in a scene, that character's (and Perform brings the scene back) */
  var go0 = stages.go0 = stages.go;
  stages.go = function (app, s) {
    var R = app.scene; if (!R) return go0(app, s);
    if (R.dive) { if (s === 'perform') return scene.go(app, 'perform'); R.stage = s; var r = go0(app, s); scene.remember(app); return r; }
    if (s !== 'perform' && R.hand === 'cast' && R.focus && R.actors[R.focus]) return scene.diveIn(app, R.focus, s);
    return scene.go(app, s);
  };

  /* the path: the scene first, whenever one is in use */
  var path0 = stages.path;
  stages.path = function (app) {
    var R = app.scene; if (!R) return path0(app);
    /* the scene's tab wears the director's chair: the scene, here as on the stage (lit when the scene is in hand) */
    var me = { id: 'scene:' + R.data.uid, name: R.data.name, glyph: AL.strip && AL.strip.chair, pic: scene.picNow ? scene.picNow(R) : null, go: function () { if (R.dive) scene.toScene(app); else { R.sel = null; app.refreshPanel(); } }, tap: function () { } };
    if (R.dive) return [me].concat(path0(app));
    var P = [me], it = R.sel ? scene.item(R, R.sel) : null;
    if (it) P.push({ id: 'item:' + it.id, name: it.name, pic: scene.picOf ? scene.picOf(R, it) : null, go: function () { }, tap: function () { } });
    return P;
  };

  /* ── remembered: the scene in use, its stage, who is in hand ── */
  scene.remember = function (app) {
    var R = app.scene; if (!R) return;
    try { localStorage.setItem('al.sceneAt', JSON.stringify({ uid: R.data.uid, stage: R.dive ? (app.mode === 'perform' ? 'perform' : app.stage) : R.stage, hand: R.hand, focus: R.focus })); } catch (e) { }
  };
  var tries = 0, tm = setInterval(function () {
    if (++tries > 40 || root.__alNoScene) { clearInterval(tm); return; }
    if (!app.ch || !app.G) return; clearInterval(tm);
    try {
      var id = localStorage.getItem('al.scene'), at = JSON.parse(localStorage.getItem('al.sceneAt') || 'null');
      if (id && !app.scene) scene.openId(app, id, at && at.uid === id ? { stage: at.stage, hand: at.hand, focus: at.focus } : { stage: 'perform' }).then(function (ok) { if (!ok) { try { localStorage.removeItem('al.scene'); } catch (e) { } } });
    } catch (e) { }
  }, 400);
})(typeof self !== 'undefined' ? self : this);
