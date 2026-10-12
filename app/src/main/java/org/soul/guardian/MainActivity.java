package org.soul.guardian;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import org.soul.guardian.config.ConfigParser;
import org.soul.guardian.core.FilterConfig;
import org.soul.guardian.data.BannedList;
import org.soul.guardian.data.ConfigRepository;
import org.soul.guardian.ui.BannedListAdapter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements BannedListAdapter.Listener {

    private ConfigRepository repo;
    private BannedListAdapter adapter;
    private boolean loading = true;

    private TextView textModuleStatus;
    private MaterialSwitch switchEnable;
    private MaterialButtonToggleGroup toggleMode;
    private TextInputEditText inputMaskSymbol;
    private RecyclerView recyclerLists;
    private TextView textEmptyLists;
    private TextInputEditText inputPreview;
    private TextView textPreviewOutput;
    private ChipGroup chipGroupTheme;
    private MaterialButtonToggleGroup toggleNight;
    private MaterialButton buttonImport;
    private MaterialSwitch switchTestMode;

    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;
    private String pendingExportName;
    private String pendingExportContent;
    private int pendingExportCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeUtil.isDynamic(this)
                ? R.style.Theme_SoulGuardian_Dynamic : ThemeUtil.themeRes(this));
        super.onCreate(savedInstanceState);
        if (ThemeUtil.isDynamic(this)) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        setContentView(R.layout.activity_main);

        repo = new ConfigRepository(this);
        repo.ensureDefaults();

        textModuleStatus = findViewById(R.id.textModuleStatus);
        switchEnable = findViewById(R.id.switchEnable);
        toggleMode = findViewById(R.id.toggleMode);
        inputMaskSymbol = findViewById(R.id.inputMaskSymbol);
        recyclerLists = findViewById(R.id.recyclerLists);
        textEmptyLists = findViewById(R.id.textEmptyLists);
        inputPreview = findViewById(R.id.inputPreview);
        textPreviewOutput = findViewById(R.id.textPreviewOutput);
        chipGroupTheme = findViewById(R.id.chipGroupTheme);
        toggleNight = findViewById(R.id.toggleNight);
        buttonImport = findViewById(R.id.buttonImport);
        switchTestMode = findViewById(R.id.switchTestMode);

        textModuleStatus.setText(isModuleActivated()
                ? R.string.xposed_activated : R.string.xposed_unactivated);

        adapter = new BannedListAdapter(this);
        recyclerLists.setLayoutManager(new LinearLayoutManager(this));
        recyclerLists.setAdapter(adapter);

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::handleImportResult);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::writeExport);

        wireListeners();
        loadState();
    }

    private void wireListeners() {
        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loading) {
                repo.setEnabled(isChecked);
                updatePreview();
            }
        });

        toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (loading || !isChecked) {
                return;
            }
            repo.setMode(checkedId == R.id.buttonBlock
                    ? FilterConfig.Mode.BLOCK : FilterConfig.Mode.MASK);
            updatePreview();
        });

        inputMaskSymbol.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (loading) {
                    return;
                }
                repo.setMaskSymbol(s == null ? "" : s.toString());
                updatePreview();
            }
        });

        chipGroupTheme.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (loading) {
                return;
            }
            int chipId = group.getCheckedChipId();
            if (chipId == View.NO_ID) {
                return;
            }
            String key = themeKeyForChip(chipId);
            if (!key.equals(ThemeUtil.getThemeKey(this))) {
                ThemeUtil.setThemeKey(this, key);
                recreate();
            }
        });

        toggleNight.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (loading || !isChecked) {
                return;
            }
            int mode = nightModeForButton(checkedId);
            if (mode != ThemeUtil.getNightMode(this)) {
                ThemeUtil.setNightMode(this, mode);
                ThemeUtil.applyNightMode(this);
            }
        });

        buttonImport.setOnClickListener(v -> importLauncher.launch(new String[]{"text/plain"}));

        switchTestMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loading) {
                repo.setTestMode(isChecked);
                updatePreview();
            }
        });

        inputPreview.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!loading) {
                    updatePreview();
                }
            }
        });
    }

    private void loadState() {
        loading = true;
        switchEnable.setChecked(repo.isEnabled());
        toggleMode.check(repo.getMode() == FilterConfig.Mode.BLOCK
                ? R.id.buttonBlock : R.id.buttonMask);
        inputMaskSymbol.setText(repo.getMaskSymbol());
        chipGroupTheme.check(themeChipId(ThemeUtil.getThemeKey(this)));
        toggleNight.check(nightButtonId(ThemeUtil.getNightMode(this)));
        switchTestMode.setChecked(repo.isTestMode());
        refreshLists();
        updatePreview();
        loading = false;
    }

    private void refreshLists() {
        List<BannedList> lists = repo.getLists();
        adapter.setData(lists, repo.getActiveListIds());
        textEmptyLists.setVisibility(lists.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updatePreview() {
        CharSequence raw = inputPreview.getText();
        String input = raw == null ? "" : raw.toString();
        FilterConfig config = new FilterConfig(
                repo.isEnabled(), repo.getMode(), repo.getMaskSymbol(), repo.isTestMode(),
                repo.getActiveWords());
        CharSequence filtered = config.filter(input);
        textPreviewOutput.setText(filtered);
    }

    /**
     * Returns whether the Xposed manager has the module enabled. Hooked by
     * {@link HookInit}; stays {@code false} when running as a plain app.
     */
    public static boolean isModuleActivated() {
        return false;
    }

    @Override
    public void onToggle(BannedList list, boolean checked) {
        if (loading) {
            return;
        }
        repo.setListSelected(list.id, checked);
        updatePreview();
    }

    @Override
    public void onExport(BannedList list) {
        List<BannedList> one = new ArrayList<>();
        one.add(list);
        startExport(one);
    }

    @Override
    public void onDelete(BannedList list) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_list_title)
                .setMessage(getString(R.string.delete_list_message, list.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    repo.deleteList(list.id);
                    refreshLists();
                    updatePreview();
                })
                .show();
    }

    private void handleImportResult(Uri uri) {
        if (uri == null) {
            return;
        }
        List<String> words;
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            words = ConfigParser.readRules(reader);
        } catch (Throwable t) {
            Snackbar.make(recyclerLists, getString(R.string.import_failed, String.valueOf(t.getMessage())),
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        if (words.isEmpty()) {
            Snackbar.make(recyclerLists, R.string.import_empty, Snackbar.LENGTH_LONG).show();
            return;
        }
        String name = queryDisplayName(uri);
        BannedList list = repo.createList(name, words);
        repo.addList(list);
        repo.setListSelected(list.id, true);
        refreshLists();
        updatePreview();
        Snackbar.make(recyclerLists,
                getString(R.string.import_success, list.name, words.size()),
                Snackbar.LENGTH_LONG).show();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the default name
        }
        return getString(R.string.section_lists);
    }

    private void startExport(List<BannedList> lists) {
        pendingExportContent = buildExportContent(lists);
        pendingExportCount = 0;
        for (BannedList list : lists) {
            pendingExportCount += list.words.size();
        }
        pendingExportName = lists.size() == 1
                ? sanitizeFileName(lists.get(0).name) + ".txt"
                : getString(R.string.export_group_name);
        exportLauncher.launch(pendingExportName);
    }

    private String buildExportContent(List<BannedList> lists) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(getString(R.string.export_header)).append('\n');
        for (BannedList list : lists) {
            sb.append("# ").append(list.name)
                    .append(getString(R.string.export_group_suffix, list.words.size())).append('\n');
            for (String rule : list.words) {
                sb.append(rule).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void writeExport(Uri uri) {
        if (uri == null) {
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out != null) {
                out.write(pendingExportContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Snackbar.make(recyclerLists,
                    getString(R.string.export_failed, String.valueOf(t.getMessage())),
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        Snackbar.make(recyclerLists,
                getString(R.string.export_success, pendingExportName, pendingExportCount),
                Snackbar.LENGTH_LONG).show();
    }

    private static String sanitizeFileName(String name) {
        String safe = name == null ? "词库" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "词库" : safe;
    }

    private int themeChipId(String key) {
        switch (key) {
            case ThemeUtil.THEME_DYNAMIC:
                return R.id.chipDynamic;
            case ThemeUtil.THEME_BLUE:
                return R.id.chipBlue;
            case ThemeUtil.THEME_GREEN:
                return R.id.chipGreen;
            case ThemeUtil.THEME_ORANGE:
                return R.id.chipOrange;
            case ThemeUtil.THEME_PURPLE:
                return R.id.chipPurple;
            case ThemeUtil.THEME_DEFAULT:
            default:
                return R.id.chipPink;
        }
    }

    private String themeKeyForChip(int chipId) {
        if (chipId == R.id.chipDynamic) {
            return ThemeUtil.THEME_DYNAMIC;
        }
        if (chipId == R.id.chipBlue) {
            return ThemeUtil.THEME_BLUE;
        }
        if (chipId == R.id.chipGreen) {
            return ThemeUtil.THEME_GREEN;
        }
        if (chipId == R.id.chipOrange) {
            return ThemeUtil.THEME_ORANGE;
        }
        if (chipId == R.id.chipPurple) {
            return ThemeUtil.THEME_PURPLE;
        }
        return ThemeUtil.THEME_DEFAULT;
    }

    private int nightButtonId(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            return R.id.buttonNightLight;
        }
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            return R.id.buttonNightDark;
        }
        return R.id.buttonNightSystem;
    }

    private int nightModeForButton(int buttonId) {
        if (buttonId == R.id.buttonNightLight) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (buttonId == R.id.buttonNightDark) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** Convenience adapter so listeners only override the callbacks they care about. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}