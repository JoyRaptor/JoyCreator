package com.fadcam.ui.faditor.avatar;

import android.hardware.camera2.CameraCharacteristics;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

import com.fadcam.ui.faditor.avatar.face.CameraPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cameras per tracker (the owner, 11-27): the phone's cameras as {@link CameraPlan} reads them, and opening what a plan says,
 * one camera or two at once (CameraX's concurrent mode: the trackers read pictures, so they open cameras as the face source
 * always has; FadCam's dual camera service records video files and is not a picture source). No face or body model here, so
 * the emulator can check the cameras on their own.
 */
public final class TrackerCameras {
    private TrackerCameras() {}

    /** The phone's cameras, in the camera system's own order (its main back camera first among the back ones). */
    @NonNull
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static List<CameraPlan.Lens> lenses(@NonNull ProcessCameraProvider p) {
        List<CameraPlan.Lens> out = new ArrayList<>();
        for (CameraInfo info : p.getAvailableCameraInfos()) {
            String id;
            float focal = 0;
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                id = c2.getCameraId();
                float[] f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (f != null) for (float v : f) if (v > 0 && (focal == 0 || v < focal)) focal = v;
            } catch (RuntimeException e) {
                continue;                                          // (a camera that will not say who it is is not offered)
            }
            ZoomState z = info.getZoomState().getValue();
            int facing = info.getLensFacing();
            out.add(new CameraPlan.Lens(id, facing == CameraSelector.LENS_FACING_FRONT, facing == CameraSelector.LENS_FACING_BACK,
                    focal, z == null ? 1f : z.getMinZoomRatio()));
        }
        return out;
    }

    /** The sets of cameras this phone can open together (empty: one at a time). */
    @NonNull
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static List<Set<String>> pairs(@NonNull ProcessCameraProvider p) {
        List<Set<String>> out = new ArrayList<>();
        try {
            for (List<CameraInfo> set : p.getAvailableConcurrentCameraInfos()) {
                Set<String> ids = new HashSet<>();
                for (CameraInfo info : set) ids.add(Camera2CameraInfo.from(info).getCameraId());
                out.add(ids);
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** The camera with this id. */
    @NonNull
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static CameraSelector selector(@NonNull String id) {
        return new CameraSelector.Builder().addCameraFilter(infos -> {
            List<CameraInfo> keep = new ArrayList<>();
            for (CameraInfo i : infos) if (id.equals(Camera2CameraInfo.from(i).getCameraId())) keep.add(i);
            return keep;
        }).build();
    }

    /** The cameras opened: one, or the face's and the body's. */
    public static final class Opened {
        @NonNull public final Camera face;
        @NonNull public final Camera body;
        public final boolean two;

        Opened(@NonNull Camera face, @NonNull Camera body, boolean two) {
            this.face = face;
            this.body = body;
            this.two = two;
        }
    }

    /**
     * Opens what the plan says: the face's camera with {@code faceUses}; with two cameras, the body's with {@code bodyUses}
     * (one camera: {@code bodyUses} is ignored, the face's pictures feed both). Sets the wide end where the plan asks for it.
     * Throws when the phone refuses (the caller falls back to one camera).
     */
    @NonNull
    public static Opened open(@NonNull ProcessCameraProvider p, @NonNull LifecycleOwner owner, @NonNull CameraPlan.Plan plan,
                              @NonNull UseCase faceUses, @NonNull UseCase bodyUses) {
        if (plan.face == null) throw new IllegalStateException("no camera");
        p.unbindAll();
        if (plan.two && plan.body != null) {
            ConcurrentCamera cc = p.bindToLifecycle(Arrays.asList(
                    new ConcurrentCamera.SingleCameraConfig(selector(plan.face.lens.id), new UseCaseGroup.Builder().addUseCase(faceUses).build(), owner),
                    new ConcurrentCamera.SingleCameraConfig(selector(plan.body.lens.id), new UseCaseGroup.Builder().addUseCase(bodyUses).build(), owner)));
            List<Camera> cams = cc.getCameras();
            Camera f = cams.get(0), b = cams.size() > 1 ? cams.get(1) : cams.get(0);
            zoom(f, plan.face.zoom);
            zoom(b, plan.body.zoom);
            return new Opened(f, b, true);
        }
        Camera c = p.bindToLifecycle(owner, selector(plan.face.lens.id), faceUses);
        zoom(c, plan.sharedZoom);
        return new Opened(c, c, false);
    }

    private static void zoom(@NonNull Camera c, float ratio) {
        if (ratio < 0.999f) c.getCameraControl().setZoomRatio(ratio);
    }
}
