package org.soul.guardian.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.soul.guardian.R;
import org.soul.guardian.data.BannedList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class BannedListAdapter extends RecyclerView.Adapter<BannedListAdapter.ViewHolder> {

    public interface Listener {
        void onToggle(BannedList list, boolean checked);

        void onExport(BannedList list);

        void onDelete(BannedList list);
    }

    private final List<BannedList> items = new ArrayList<>();
    private final Listener listener;
    private Set<String> selectedIds;

    public BannedListAdapter(Listener listener) {
        this.listener = listener;
    }

    /** Pass {@code null} to mark every list as selected (default); an empty set selects none. */
    public void setData(List<BannedList> lists, Set<String> selectedIds) {
        items.clear();
        if (lists != null) {
            items.addAll(lists);
        }
        this.selectedIds = selectedIds;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_banned_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final BannedList list = items.get(position);
        holder.name.setText(list.name);
        holder.count.setText(holder.itemView.getContext()
                .getString(R.string.word_count, list.words.size()));
        boolean selected = selectedIds == null || selectedIds.contains(list.id);
        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(selected);
        holder.checkBox.setOnCheckedChangeListener((button, checked) ->
                listener.onToggle(list, checked));
        holder.itemView.setOnClickListener(v ->
                holder.checkBox.toggle());
        holder.export.setOnClickListener(v -> listener.onExport(list));
        holder.delete.setOnClickListener(v -> listener.onDelete(list));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCheckBox checkBox;
        final TextView name;
        final TextView count;
        final ImageButton export;
        final ImageButton delete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkSelect);
            name = itemView.findViewById(R.id.textName);
            count = itemView.findViewById(R.id.textCount);
            export = itemView.findViewById(R.id.buttonExport);
            delete = itemView.findViewById(R.id.buttonDelete);
        }
    }
}