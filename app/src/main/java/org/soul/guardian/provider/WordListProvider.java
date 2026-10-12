package org.soul.guardian.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.soul.guardian.config.ConfigKeys;
import org.soul.guardian.core.FilterConfig;
import org.soul.guardian.data.ConfigRepository;

import java.util.List;

/**
 * Serves the module's current banned-word configuration to hooked processes. The host process
 * queries this provider (pulling the latest list on demand) and watches {@link WordsContract#RULES_URI}
 * through a {@code ContentObserver}; the module fires {@code notifyChange} after every config
 * change. The provider runs inside the module app, so the host's query may even start this
 * process if it is not running yet.
 */
public final class WordListProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        ConfigRepository repo = new ConfigRepository(context);
        String path = uri.getPath();
        if ("/rules".equals(path)) {
            List<String> rules = repo.getActiveWords();
            MatrixCursor cursor = new MatrixCursor(new String[]{WordsContract.COLUMN_RULE});
            for (String rule : rules) {
                cursor.addRow(new Object[]{rule});
            }
            cursor.setNotificationUri(context.getContentResolver(), WordsContract.RULES_URI);
            return cursor;
        }
        if ("/config".equals(path)) {
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    WordsContract.COLUMN_ENABLED, WordsContract.COLUMN_MODE, WordsContract.COLUMN_MASK,
                    WordsContract.COLUMN_TEST_MODE});
            cursor.addRow(new Object[]{
                    repo.isEnabled() ? 1 : 0,
                    repo.getMode() == FilterConfig.Mode.BLOCK
                            ? ConfigKeys.MODE_BLOCK : ConfigKeys.MODE_MASK,
                    repo.getMaskSymbol(),
                    repo.isTestMode() ? 1 : 0});
            cursor.setNotificationUri(context.getContentResolver(), WordsContract.CONFIG_URI);
            return cursor;
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if ("/rules".equals(path)) {
            return "vnd.android.cursor.dir/vnd.soul.guardian.rule";
        }
        if ("/config".equals(path)) {
            return "vnd.android.cursor.item/vnd.soul.guardian.config";
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}