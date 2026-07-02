package com.fadcam.ui.faditor.ai;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONObject;

/**
 * Headless activity that applies an EditScript to a project without showing UI.
 *
 * <p>Intended for external AI agents, automation tools (Tasker), or ADB:</p>
 * <pre>
 * adb shell am start \
 *   -n com.fadcam.beta/.ui.faditor.ai.ApplyEditsActivity \
 *   --es project_id "PROJECT_UUID" \
 *   --es edit_script '{"version":1,"operations":[...]}'
 * </pre>
 *
 * <p>Returns a result JSON string as an intent extra in the response:
 * {@code "ok": true/false, "message": "...", "applied": N}</p>
 *
 * <p>The activity finishes immediately — it does not display any UI.</p>
 */
public class ApplyEditsActivity extends Activity {

    private static final String TAG = "ApplyEdits";

    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_EDIT_SCRIPT = "edit_script";
    public static final String EXTRA_RESULT = "result";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String projectId = intent.getStringExtra(EXTRA_PROJECT_ID);
        String scriptJson = intent.getStringExtra(EXTRA_EDIT_SCRIPT);

        if (projectId == null || projectId.isEmpty()) {
            finishWithError("Missing project_id");
            return;
        }
        if (scriptJson == null || scriptJson.isEmpty()) {
            finishWithError("Missing edit_script");
            return;
        }

        try {
            ProjectStorage storage = new ProjectStorage(this);
            FaditorProject project = storage.load(projectId);
            if (project == null) {
                finishWithError("Project not found: " + projectId);
                return;
            }

            EditScript script = EditScript.fromJson(scriptJson);
            EditScriptApplier applier = new EditScriptApplier();
            applier.setContext(this);

            String validationError = applier.validate(project, script);
            if (validationError != null) {
                finishWithError("Validation failed: " + validationError);
                return;
            }

            EditScriptApplier.Result result = applier.apply(project, script);
            if (!result.success) {
                finishWithError("Apply failed: " + result.error);
                return;
            }

            storage.save(project);
            AIChatState.signalModified(projectId);

            JSONObject response = new JSONObject();
            response.put("ok", true);
            response.put("message", "Applied " + result.appliedCount + " operations");
            response.put("applied", result.appliedCount);
            response.put("description", script.getDescription());

            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_RESULT, response.toString());
            setResult(RESULT_OK, resultIntent);
            FLog.i(TAG, "EditScript applied: " + result.appliedCount
                    + " ops to project " + projectId);
            finish();

        } catch (EditScript.EditScriptException e) {
            finishWithError("EditScript parse error: " + e.getMessage());
        } catch (Exception e) {
            finishWithError("Error: " + e.getMessage());
        }
    }

    private void finishWithError(@NonNull String message) {
        FLog.e(TAG, "ApplyEdits failed: " + message);
        try {
            JSONObject response = new JSONObject();
            response.put("ok", false);
            response.put("message", message);
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_RESULT, response.toString());
            setResult(RESULT_CANCELED, resultIntent);
        } catch (Exception ignored) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
