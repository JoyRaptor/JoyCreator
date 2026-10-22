package cc.joycreator.joybrush.android.chrome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.*
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.UnibrushCanvasPort
import cc.joycreator.joybrush.androidkit.UnibrushResources
import cc.joycreator.joybrush.androidkit.brush.*
import cc.joycreator.joybrush.androidkit.preview.*
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Executor

/** Outside-only D9 host proposal. All public calls/callbacks are main-thread except worker closures. */
class UnibrushHostController(
    private val activity:Activity, private val kit:ChromeKit, private val canvas:JbCanvasView, private val port:UnibrushCanvasPort,
    prefs:SharedPreferences, private val popovers:Popovers, private val cache:PreviewSource<Bitmap>,
    shipped:List<BrushPreset>, private val alignEnd:()->Boolean,
    private val libraryChanged:(List<BrushPreset>)->Unit,
    private val takeUp:(BrushPreset)->Unit, private val refusal:(String)->Unit,
) {
    private val worker=Executors.newSingleThreadExecutor()
    private val store=createAndroidUserModeStore(activity)
    private val builtins=UnibrushResources()
    private val preferenceStore=StabilizerPreferenceStore(prefs)
    private val paletteStore=UnibrushPalettePreferenceStore(prefs)
    private val secondaryInk=UnibrushSecondaryInkSession({port.secondaryColorArgb},{port.secondaryColorArgb=it},{ paletteStore.secondaryColorArgb=it })
    private val base:List<BrushPreset>
    private var users:List<BrushPreset> = emptyList()
    @Volatile private var session:DraftBrushImageSession?=null
    private var selectionRevision=0L
    private var selectionWork:Future<*>?=null
    private var sheetRevision=0L
    @Volatile private var destroyed=false
    private var sheet:UnibrushSettingsView?=null
    private var previewAsk:PreviewCancel?=null
    private var previewPen=PreviewPenOverride(.7f,0f)
    private var previewImage:ImageView?=null
    private var picker:Pick?=null
    private var pickerWork:Future<*>?=null
    @Volatile private var pickerGeneration=0L
    private var sheetPreparing=false
    private var saveDialog:AlertDialog?=null
    private var savingMode=false
    private class ResourcePreview(val preset:BrushPreset,val width:Int,val height:Int,val style:BrushPreviewStyle,
        val actual:StabilizerSpec?,val pen:PreviewPenOverride?)
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val resourceLane=SerialResourcePreviewLane<ResourcePreview,BrushPreviewRequest,Bitmap>(
        io=worker,ui=Executor{task->check(mainHandler.post(task)){"The main preview executor is unavailable"}},
        prepare={request->
            val selected=session?.current()?.takeIf{it.recipe.id==request.preset.id&&it.resources.paths==BrushAssetPaths.required(request.preset).toSet()}
            val pngs=selected?.resources?:resources(request.preset)
            PreviewResourceFingerprint.of(pngs)
            BrushPreviewRequest.snapshot(request.preset,request.width,request.height,request.style,pngs,request.actual,request.pen)
        },submit={snapshot,ready->cache.request(snapshot,ready)},
        onError={error->say(error as? Exception?:Exception("The preview failed",error))})
    private class Pick(val epoch:Long,val session:DraftBrushImageSession,val request:BrushImagePickRequest,
        val callback:(List<String>?)->Unit)

    init {
        val modes=UnibrushModes.presets()
        val reserved=modes.map{it.id}.toSet()
        require(shipped.filter{it.id in reserved}.all{isFamily(it)}){"A historical preset occupies a reserved Unibrush id"}
        base=shipped+modes.filter{mode->shipped.none{it.id==mode.id}}
        port.stabilizerPreference=preferenceStore.load()
        port.secondaryColorArgb=paletteStore.secondaryColorArgb
        publishLibrary()
    }
    fun isFamily(p:BrushPreset)=p.engine=="stamp"&&p.extensions[UnibrushModes.FAMILY_KEY] in setOf("builtin","user")
    fun isReady(p:BrushPreset):Boolean=session?.current()?.let{
        it.recipe.id==p.id&&it.resources.paths==BrushAssetPaths.required(p).toSet()
    }?:false
    fun cancelSelection(){secondaryInk.cancel();selectionRevision++;selectionWork?.cancel(true);selectionWork=null;cancelPick()}
    fun library():List<BrushPreset> {
        val edited=session?.current()?.recipe
        return (base+users).map{p->edited?.takeIf{it.id==p.id}?:p}
    }
    private fun publishLibrary()=libraryChanged(library())
    private fun main(block:()->Unit)=activity.runOnUiThread { if(!destroyed)block() }
    private fun say(error:Exception)=refusal(error.message?:"The brush could not be prepared")

    /** Caller must preserve saved ToolMemory until success AND initial user-resource selection completes. */
    fun start(ready:(Boolean)->Unit) {
        worker.execute {
            try {
                val catalog=store.loadDefinitions();val definitions=catalog.definitions.map{it.preset}
                main { users=definitions;publishLibrary();if(catalog.refused.isNotEmpty())refusal("Some saved modes could not be loaded; valid modes remain available");ready(true) }
            } catch(error:Exception) { main {say(error);ready(false)} }
        }
    }
    private fun resources(p:BrushPreset):FrozenBrushPngs {
        if(BrushAssetPaths.required(p).isEmpty())return FrozenBrushPngs.empty()
        return (if(p.extensions[UnibrushModes.FAMILY_KEY]=="user")store.pngs(p) else builtins.pngs(p))
            ?:throw BrushException("This mode needs its complete frozen PNG resources")
    }
    fun select(p:BrushPreset, selected:((BrushPreset)->Unit)?=null) {
        secondaryInk.cancel()
        val revision=++selectionRevision
        selectionWork?.cancel(true);selectionWork=null
        popovers.close()
        cancelPick();sheetRevision++;previewAsk?.cancel();sheet=null;previewImage=null
        val current=session?.current()?.takeIf{it.recipe.id==p.id&&it.resources.paths==BrushAssetPaths.required(p).toSet()}
        selectionWork=worker.submit {
            try {
                val made=AndroidBrushDraftImages.session(p,current?.resources?:resources(p))
                PreviewResourceFingerprint.of(made.current().resources)
                main { if(revision==selectionRevision) {
                    try {
                        admitPreviewContext(made.current())
                        install(made){(selected?:takeUp)(made.current().recipe)}
                    }
                    catch(error:Exception){say(error)}
                } }
            } catch(error:Exception) {main{if(revision==selectionRevision)say(error)}}
        }
    }
    private fun install(made:DraftBrushImageSession, accepted:()->Unit = {}) {
        val pair=made.current();val old=session;val oldProvider=port.brushPngs;val oldPreset=canvas.preset
        val stable:(BrushPreset)->FrozenBrushPngs?={p->
            if(p.id!=pair.recipe.id||BrushAssetPaths.required(p).isEmpty())null
            else {pair.resources.requirePaths(BrushAssetPaths.required(p));pair.resources}
        }
        try {
            session=made;port.brushPngs=stable;publishLibrary();canvas.preset=pair.recipe;accepted()
        } catch(error:Exception) {
            session=old;port.brushPngs=oldProvider;canvas.preset=oldPreset;publishLibrary();throw error
        }
    }

    /** Metadata-only queue; one resource-bearing IO/cache/GL request owns the encoded-image lease. */
    val drawerPreviews:PreviewSource<Bitmap> = object:PreviewSource<Bitmap> {
        override fun request(p:BrushPreset,w:Int,h:Int,style:BrushPreviewStyle,onReady:(Bitmap)->Unit):PreviewCancel {
            if(!isFamily(p))return cache.request(p,w,h,style,onReady)
            return requestPreview(p,w,h,style,null,onReady)
        }
        override fun request(snapshot:BrushPreviewRequest,onReady:(Bitmap)->Unit):PreviewCancel {
            require(snapshot.pngs==null){"Encoded preview resources must enter through the host's serial admission lane"}
            return cache.request(snapshot,onReady)
        }
    }
    private fun requestPreview(p:BrushPreset,w:Int,h:Int,style:BrushPreviewStyle,pen:PreviewPenOverride?,ready:(Bitmap)->Unit):PreviewCancel {
        val preset=BrushJson.decodeChecked(BrushJson.encode(p))
        val paletteStyle=style.copy(secondaryInk=port.secondaryColorArgb?:0xFFFFFFFF.toInt())
        val actual=BrushPreviewRequest.defaultActualStabilizer(preset,port.stabilizerPreference.copy(),
            if(canvas.smoothingFromUser)canvas.smoothing else preset.smoothing)
        if(BrushAssetPaths.required(preset).isEmpty())
            return cache.request(BrushPreviewRequest.snapshot(preset,w,h,paletteStyle,null,actual,pen),ready)
        return resourceLane.enqueue(ResourcePreview(preset,w,h,paletteStyle,actual,pen?.copy()),ready)
    }
    /** Activity maker signals the exact cache snapshot; terminal is independent of nullable delivery. */
    fun previewStarted(snapshot:BrushPreviewRequest)=resourceLane.started(snapshot)
    fun previewRetired(snapshot:BrushPreviewRequest)=resourceLane.retired(snapshot)

    fun openSettings() {
        val active=session?:return
        val epoch=++sheetRevision
        val image=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_XY}
        image.addOnLayoutChangeListener{_,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom->
            if(right-left!=oldRight-oldLeft||bottom-top!=oldBottom-oldTop)redrawPreview(epoch)
        }
        previewImage=image
        val view=UnibrushSettingsView(kit,active.current().recipe,port.stabilizerPreference,image,
            onChange={recipe,_->
                val now=session?:throw BrushException("Choose the mode again")
                val imported=now.pending()?.pair?.takeIf{it.resources.paths==BrushAssetPaths.required(recipe).toSet()}
                // All bytes here were fully decoded on IO selection/import. These repeated model edits
                // perform bounded structural admission only; no Bitmap decoder runs on the model thread.
                val candidate=DraftBrushImageSession(recipe,imported?.resources?:now.current().resources)
                admitPreviewContext(candidate.current())
                install(candidate){takeUp(candidate.current().recipe)}
                redrawPreview(epoch)
            },
            onPreferenceChange={next->changePreference(next);redrawPreview(epoch)},
            onNewBrush={recipe->askSaveAs(recipe,epoch)},
            onDone={popovers.close()},
            onPreviewInput={pressure,tilt->previewPen=PreviewPenOverride(pressure,tilt);redrawPreview(epoch)},
            chooseAssets={request,done->chooseImages(request,done,epoch)},
            editProfilePoints={profile,done->UnibrushProfileDialogs.show(kit,profile,done)},
            onChooseSecondaryColour={chooseSecondaryColour(epoch)},
        )
        sheet=view
        canvas.onPenReading={p,t,o->if(epoch==sheetRevision)view.showReading(p,t,o)}
        popovers.showSheet(view,600f,alignEnd(),onClosed={
            if(epoch==sheetRevision){sheetRevision++;sheet=null;previewImage=null;previewAsk?.cancel();previewAsk=null;canvas.onPenReading=null;cancelPick();secondaryInk.cancel()}
        },modal=false)
        image.post{redrawPreview(epoch)}
    }
    private fun chooseSecondaryColour(epoch:Long) {
        if (destroyed||epoch!=sheetRevision) return
        val choice=secondaryInk.begin{!destroyed&&epoch==sheetRevision}
        com.fadcam.ui.faditor.tools.ColorPickerDialog.show(activity,"Second colour",choice.initialArgb,false,
            {live->if(live!=null){choice.preview(live);redrawPreview(epoch)}},
            {picked->if(picked!=null)try{choice.confirm(picked);redrawPreview(epoch)}catch(error:Exception){say(error)}},
            null,{choice.cancel();redrawPreview(epoch)})
    }
    private fun redrawPreview(epoch:Long) {
        val image=previewImage?:return
        if(epoch!=sheetRevision||image.width<=0||image.height<=0)return
        val pair=session?.current()?:return
        previewAsk?.cancel()
        try{previewAsk=requestPreview(pair.recipe,image.width,image.height,BrushPreviewStyle(ink=canvas.strokeColor,secondaryInk=port.secondaryColorArgb?:0xFFFFFFFF.toInt()),previewPen){bitmap->
            if(epoch==sheetRevision&&previewImage===image)image.setImageBitmap(bitmap)
        }}catch(error:Exception){say(error)}
    }
    private fun admitPreviewContext(pair:BrushImagePair,preference:StabilizerPreference=port.stabilizerPreference) {
        val p=pair.recipe
        val actual=BrushPreviewRequest.defaultActualStabilizer(p,preference,
            if(canvas.smoothingFromUser)canvas.smoothing else p.smoothing)
        actual?.let{
            require(it.problems().isEmpty()){it.problems().joinToString("; ")}
            require(!cc.joycreator.joybrush.core.vector.LineStyle.of(p.line).isWire){"Actual stabilizer settings on wire previews are not supported"}
        }
        // Complete model/PNG framing was admitted already. Snapshot/fingerprint creation is deferred
        // to the serialized IO resource lane, including newly pruned owners from ordinary model edits.
    }
    private fun changePreference(next:StabilizerPreference) {
        require(next.mine.problems().isEmpty()){next.mine.problems().joinToString("; ")}
        session?.current()?.let{admitPreviewContext(it,next)}
        preferenceStore.save(next);port.stabilizerPreference=next
    }

    private fun chooseImages(request:UnibrushAssetRequest,done:(List<String>?)->Unit,epoch:Long) {
        if(request.slot==UnibrushAssetSlot.tipTexture){
            val choices=PackagedBrushGrains.choices()
            AlertDialog.Builder(activity).setTitle("Tip texture")
                .setItems(choices.map{it.label}.toTypedArray()){_,index->if(epoch==sheetRevision)done(listOf(choices[index].fileName))}
                .setNegativeButton("Cancel"){_,_->done(null)}.show()
            return
        }
        check(!sheetPreparing){"Finish the previous image preparation first"}
        cancelPick()
        val active=session?:throw BrushException("Choose the mode again")
        val slot=if(request.slot==UnibrushAssetSlot.sampledTips)BrushImageSlot.sampled else BrushImageSlot.dual
        picker=Pick(epoch,active,active.requestPick(slot),done)
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE);type="image/png"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,slot==BrushImageSlot.sampled)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {activity.startActivityForResult(intent,REQUEST_PNG)}catch(error:Exception){cancelPick();throw error}
    }
    fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?):Boolean {
        if(requestCode!=REQUEST_PNG)return false
        val pick=picker.also{picker=null}?:return true
        if(resultCode!=Activity.RESULT_OK){pick.callback(null);return true}
        val uris=ArrayList<Uri>()
        data?.clipData?.let{clip->
            val cap=if(pick.request.slot==BrushImageSlot.sampled)16 else 1
            if(clip.itemCount>cap){refusal("Too many PNG images were selected");pick.callback(null);return true}
            for(i in 0 until clip.itemCount)uris.add(clip.getItemAt(i).uri)
        }
        if(uris.isEmpty())data?.data?.let{uris.add(it)}
        if(uris.isEmpty()){pick.callback(null);return true}
        sheetPreparing=true
        val generation=++pickerGeneration
        pickerWork=worker.submit {
            try {
                val sources=uris.map{uri->
                    var name:String?=null;var size:Long?=null
                    activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->
                        if(cursor.moveToFirst()){
                            val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)?.take(256)
                            val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s).takeIf{it>=0}
                        }
                    }
                    BrushPickedPng(name,size){activity.contentResolver.openInputStream(uri)?:throw BrushException("The selected PNG stream is unavailable")}
                }
                val prepared=AndroidBrushDraftImages.loader().prepare(pick.request,sources){destroyed||generation!=pickerGeneration||Thread.currentThread().isInterrupted}
                PreviewResourceFingerprint.of(prepared.pair.resources)
                main {
                    if(generation!=pickerGeneration)return@main
                    sheetPreparing=false;pickerWork=null
                    if(pick.epoch==sheetRevision&&session===pick.session){pick.session.stage(prepared);pick.callback(prepared.selectedPaths)}
                }
            }catch(error:Exception){main{if(generation==pickerGeneration){sheetPreparing=false;pickerWork=null;if(pick.epoch==sheetRevision){say(error);pick.callback(null)}}}}
        }
        return true
    }
    private fun cancelPick(){pickerGeneration++;picker?.callback?.invoke(null);picker=null;pickerWork?.cancel(true);pickerWork=null;sheetPreparing=false}

    private fun askSaveAs(recipe:BrushPreset,epoch:Long) {
        check(!savingMode){"Wait for the current new mode to finish saving"}
        if(saveDialog?.isShowing==true)return
        val pair=session?.current()?:return
        require(pair.recipe.id==recipe.id){"The brush selection changed"}
        val name=EditText(activity).apply{setSingleLine();hint="Mode name";setText(recipe.name);selectAll()}
        val dialog=AlertDialog.Builder(activity).setTitle("New brush").setView(name)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        saveDialog=dialog
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(savingMode){name.error="Wait for the current save";return@setOnClickListener}
            val words=name.text.toString().trim()
            if(words.isBlank()){name.error="Choose a mode name";return@setOnClickListener}
            savingMode=true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            worker.execute {
                try {
                    val saved=store.saveAs(words,recipe,pair.resources)
                    val catalog=store.loadDefinitions();val selected=store.select(saved.id)
                    val made=DraftBrushImageSession(selected.preset,selected.images?:FrozenBrushPngs.empty())
                    PreviewResourceFingerprint.of(made.current().resources)
                    main {
                        savingMode=false
                        users=catalog.definitions.map{it.preset};publishLibrary();dialog.dismiss();saveDialog=null
                        if(epoch==sheetRevision){popovers.close();install(made);takeUp(made.current().recipe);openSettings()}
                        else refusal("${saved.name} was saved as a new mode")
                    }
                }catch(error:Exception){main{
                    savingMode=false
                    if(error is UserModeStoreException&&error.publishedId!=null){
                        dialog.dismiss();saveDialog=null
                        // Refresh an already-published mode, but never replace a later owner selection.
                        start{loaded->if(loaded&&epoch==sheetRevision)library().firstOrNull{it.id==error.publishedId}?.let{select(it)}}
                    }else dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                    say(error)
                }}
            }
        }}
        dialog.setOnDismissListener{if(saveDialog===dialog)saveDialog=null};dialog.show()
    }

    fun openStabilizer() {
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(kit.dpi(12f),0,kit.dpi(12f),0)}
        var value=port.stabilizerPreference
        fun update(next:StabilizerPreference){changePreference(next);value=next;redrawPreview(sheetRevision)}
        fun <T> choice(title:String,values:List<T>,current:T,caption:(T)->String,onPick:(T)->Unit){
            body.addView(TextView(activity).apply{text=title;setTextColor(kit.p.drawerInk)})
            val spinner=Spinner(activity)
            spinner.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,values.map(caption))
            spinner.setSelection(values.indexOf(current));var selected=current
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(parent:AdapterView<*>?){}
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                    val next=values[position];if(next!=selected){onPick(next);selected=next}
                }
            };body.addView(spinner)
        }
        choice("Whose settings",StabilizerPolicy.entries.toList(),value.policy,{if(it==StabilizerPolicy.brushSuggestion)"Each brush" else "Always mine"}){update(value.copy(policy=it))}
        choice("My stabilizer",StabilizerMode.entries.toList(),value.mine.mode,{it.name.replace(Regex("([a-z])([A-Z])"),"$1 $2")}){update(value.copy(mine=value.mine.copy(mode=it)))}
        fun slider(title:String,max:Int,initial:Int,displayOffset:Int=0,change:(Int)->Unit){
            val label=TextView(activity).apply{text="$title: ${initial+displayOffset}";setTextColor(kit.p.drawerInk)};body.addView(label)
            body.addView(SeekBar(activity).apply{this.max=max;progress=initial;contentDescription=title
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser){change(progress);label.text="$title: ${progress+displayOffset}"}}
                    override fun onStartTrackingTouch(bar:SeekBar?){}
                    override fun onStopTrackingTouch(bar:SeekBar?){}
                })})
        }
        slider("Strength %",100,(value.mine.strength*100).toInt()){update(value.copy(mine=value.mine.copy(strength=it/100f)))}
        slider("String radius · screen px",512,value.mine.radiusPx.toInt()){update(value.copy(mine=value.mine.copy(radiusPx=it.toFloat())))}
        slider("Average samples",255,value.mine.windowSize-1,displayOffset=1){update(value.copy(mine=value.mine.copy(windowSize=it+1)))}
        val scroll=ScrollView(activity).apply{addView(body)}
        AlertDialog.Builder(activity).setTitle("Stabilizer").setView(scroll).setPositiveButton("Done",null).show()
    }
    fun close() {
        secondaryInk.cancel()
        destroyed=true;selectionRevision++;sheetRevision++;previewAsk?.cancel();previewAsk=null
        selectionWork?.cancel(true);selectionWork=null
        resourceLane.close()
        cancelPick();saveDialog?.dismiss();saveDialog=null
        sheet=null;previewImage=null;canvas.onPenReading=null;worker.shutdownNow()
    }
    companion object {const val REQUEST_PNG=4191}
}
