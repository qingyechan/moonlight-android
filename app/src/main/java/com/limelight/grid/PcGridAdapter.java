package com.limelight.grid;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.BridgeUiPrefs;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                String leftName = BridgeUiPrefs.getDisplayName(context, lhs.details);
                String rightName = BridgeUiPrefs.getDisplayName(context, rhs.details);
                return leftName.toLowerCase().compareTo(rightName.toLowerCase());
            }
        });
    }

    public void refreshSorting() {
        sortList();
        notifyDataSetChanged();
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        imgView.setImageResource(R.drawable.ic_computer);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
        }
        else {
            imgView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        String displayName = BridgeUiPrefs.getDisplayName(context, obj.details);
        txtView.setText(displayName);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            txtView.setAlpha(1.0f);
        }
        else {
            txtView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.55f);
            overlayView.setVisibility(View.VISIBLE);
        }
        // We must check if the status is exactly online and unpaired
        // to avoid colliding with the loading spinner when status is unknown
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        View statusDot = parentView.findViewById(R.id.grid_status_dot);
        TextView statusText = parentView.findViewById(R.id.grid_status_text);
        if (statusDot != null && statusText != null) {
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                if (obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
                    statusDot.setBackgroundResource(R.drawable.dot_warn);
                    statusText.setText(R.string.pc_status_unpaired);
                    statusText.setTextColor(context.getResources().getColor(R.color.bridge_status_warn));
                }
                else {
                    statusDot.setBackgroundResource(R.drawable.dot_online);
                    statusText.setText(R.string.pc_status_online);
                    statusText.setTextColor(context.getResources().getColor(R.color.bridge_status_online));
                }
            }
            else if (obj.details.state == ComputerDetails.State.OFFLINE) {
                statusDot.setBackgroundResource(R.drawable.dot_offline);
                statusText.setText(R.string.pc_status_offline);
                statusText.setTextColor(context.getResources().getColor(R.color.bridge_text_secondary));
            }
            else {
                statusDot.setBackgroundResource(R.drawable.dot_warn);
                statusText.setText(R.string.pc_status_checking);
                statusText.setTextColor(context.getResources().getColor(R.color.bridge_text_secondary));
            }
        }
    }
}
