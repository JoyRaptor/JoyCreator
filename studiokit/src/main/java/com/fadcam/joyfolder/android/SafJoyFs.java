package com.fadcam.joyfolder.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link JoyFs} over a folder the user picked with Android's system picker (a SAF "tree").
 *
 * <p>Document ids are looked up by walking names from the root and remembered, so a path costs
 * one query per level once. Any rename, move or delete forgets what it may have invalidated.</p>
 */
public final class SafJoyFs implements JoyFs {

    private static final String[] COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private final ContentResolver resolver;
    private final Uri tree;
    private final String rootId;
    private final Map<String, String> idByPath = new HashMap<>();

    public SafJoyFs(Context context, Uri treeUri) {
        this.resolver = context.getApplicationContext().getContentResolver();
        this.tree = treeUri;
        this.rootId = DocumentsContract.getTreeDocumentId(treeUri);
        idByPath.put("", rootId);
    }

    public Uri treeUri() {
        return tree;
    }

    /** A content URI for the file at {@code rel} that carries the folder's permission, or null. */
    public Uri documentUri(String rel) throws IOException {
        String id = idOf(rel);
        return id == null ? null : DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    /**
     * The path of a document URI inside this folder, or null when it is not inside it. Lets a
     * project that already points into the folder be written back with folder-relative paths.
     */
    public String relOf(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())
                || tree.getAuthority() == null || !tree.getAuthority().equals(uri.getAuthority())) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (docId == null) return null;
        if (docId.equals(rootId)) return "";
        // Phone storage and SD cards use path ids: "primary:Joy Creator/Lecture 3.mp4".
        if (rootId.endsWith(":") && docId.startsWith(rootId)) return docId.substring(rootId.length());
        return docId.startsWith(rootId + "/") ? docId.substring(rootId.length() + 1) : null;
    }

    // ── lookups ──────────────────────────────────────────────────────────

    private synchronized String idOf(String rel) throws IOException {
        if (rel == null) rel = "";
        String cached = idByPath.get(rel);
        if (cached != null) return cached;
        String parentId = idOf(Placement.parentOf(rel));
        if (parentId == null) return null;
        String name = Placement.nameOf(rel);
        for (Row r : query(parentId)) {
            if (r.name.equals(name)) {
                idByPath.put(rel, r.id);
                return r.id;
            }
        }
        return null;
    }

    private synchronized void forget(String rel) {
        String prefix = rel + "/";
        idByPath.keySet().removeIf(p -> p.equals(rel) || p.startsWith(prefix));
    }

    private static final class Row {
        String id, name, mime;
        long size, modified;
    }

    private List<Row> query(String parentId) throws IOException {
        List<Row> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = resolver.query(kids, COLUMNS, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getString(0);
                r.name = c.getString(1);
                r.mime = c.getString(2);
                r.size = c.isNull(3) ? 0 : c.getLong(3);
                r.modified = c.isNull(4) ? 0 : c.getLong(4);
                out.add(r);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("Joy Creator folder is not reachable: " + e.getMessage(), e);
        }
        return out;
    }

    private static boolean isDir(Row r) {
        return Document.MIME_TYPE_DIR.equals(r.mime);
    }

    // ── JoyFs ────────────────────────────────────────────────────────────

    @Override
    public List<Entry> list(String relDir) throws IOException {
        List<Entry> out = new ArrayList<>();
        String id = idOf(relDir);
        if (id == null) return out;
        for (Row r : query(id)) {
            synchronized (this) {
                idByPath.put(Placement.join(relDir, r.name), r.id);
            }
            out.add(new Entry(r.name, isDir(r), r.size, r.modified));
        }
        return out;
    }

    @Override
    public Entry stat(String rel) throws IOException {
        if (rel == null || rel.isEmpty()) return new Entry("", true, 0, 0);
        String parentId = idOf(Placement.parentOf(rel));
        if (parentId == null) return null;
        String name = Placement.nameOf(rel);
        for (Row r : query(parentId)) {
            if (r.name.equals(name)) {
                synchronized (this) {
                    idByPath.put(rel, r.id);
                }
                return new Entry(r.name, isDir(r), r.size, r.modified);
            }
        }
        synchronized (this) {
            idByPath.remove(rel);
        }
        return null;
    }

    @Override
    public InputStream open(String rel) throws IOException {
        Uri uri = documentUri(rel);
        if (uri == null) throw new FileNotFoundException(rel);
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new FileNotFoundException(rel);
        return in;
    }

    @Override
    public OutputStream create(String rel) throws IOException {
        String dir = Placement.parentOf(rel);
        String name = Placement.nameOf(rel);
        mkdirs(dir);
        Uri uri = documentUri(rel);
        if (uri == null) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(dir));
            // octet-stream: the provider must not append an ending of its own choosing.
            uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name);
            if (uri == null) throw new IOException("Could not create " + rel);
            if (stat(rel) == null) {
                // Some providers adjust the name on creation ("x (1).png"). Put it back exactly.
                Uri fixed = null;
                try {
                    fixed = DocumentsContract.renameDocument(resolver, uri, name);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                }
                if (fixed != null) uri = fixed;
                forget(rel);
                if (stat(rel) == null) {
                    DocumentsContract.deleteDocument(resolver, uri);
                    throw new IOException("The folder would not keep the name " + name);
                }
                uri = documentUri(rel);
            }
        }
        final ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rwt");
        if (pfd == null) throw new IOException("Could not open " + rel);
        final FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
        // Sync before close, so a rename that follows never exposes empty bytes.
        return new java.io.FilterOutputStream(fos) {
            @Override public void write(byte[] b, int off, int len) throws IOException { fos.write(b, off, len); }
            @Override public void close() throws IOException {
                try {
                    fos.flush();
                    fos.getFD().sync();
                } finally {
                    fos.close();
                    pfd.close();
                }
            }
        };
    }

    @Override
    public boolean rename(String rel, String newName) throws IOException {
        String dir = Placement.parentOf(rel);
        if (stat(Placement.join(dir, newName)) != null) return false;
        Uri uri = documentUri(rel);
        if (uri == null) return false;
        Uri renamed;
        try {
            renamed = DocumentsContract.renameDocument(resolver, uri, newName);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
        forget(rel);
        if (renamed == null) return stat(Placement.join(dir, newName)) != null;
        return stat(Placement.join(dir, newName)) != null;
    }

    @Override
    public boolean move(String rel, String newParentRel) throws IOException {
        String name = Placement.nameOf(rel);
        if (stat(Placement.join(newParentRel, name)) != null) return false;
        mkdirs(newParentRel);
        Uri uri = documentUri(rel);
        if (uri == null) return false;
        Uri from = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(Placement.parentOf(rel)));
        Uri to = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(newParentRel));
        Uri moved = null;
        try {
            moved = DocumentsContract.moveDocument(resolver, uri, from, to);
        } catch (UnsupportedOperationException | IllegalStateException | IllegalArgumentException e) {
            moved = null;
        }
        forget(rel);
        if (moved != null) return true;
        // Providers without move support: copy, then delete the original (files only).
        Entry e = stat(rel);
        if (e == null || e.dir) return false;
        String target = Placement.join(newParentRel, name);
        try (InputStream in = open(rel); OutputStream out = create(target)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return delete(rel);
    }

    @Override
    public void mkdirs(String relDir) throws IOException {
        if (relDir == null || relDir.isEmpty()) return;
        if (idOf(relDir) != null) return;
        String parent = Placement.parentOf(relDir);
        mkdirs(parent);
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(parent));
        Uri made = DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, Placement.nameOf(relDir));
        if (made == null) throw new IOException("Could not create folder " + relDir);
        synchronized (this) {
            idByPath.put(relDir, DocumentsContract.getDocumentId(made));
        }
    }

    @Override
    public boolean delete(String rel) throws IOException {
        Uri uri = documentUri(rel);
        if (uri == null) return false;
        boolean ok;
        try {
            ok = DocumentsContract.deleteDocument(resolver, uri);
        } catch (IllegalStateException | IllegalArgumentException e) {
            ok = false;
        }
        forget(rel);
        return ok;
    }
}
