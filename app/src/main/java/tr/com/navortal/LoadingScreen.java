package tr.com.navortal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

/** Site yüklenirken karşılama ekranının üstünde duran geçiş ekranı (ilerleme çubuğu, logo, "Vazgeç"). */
final class LoadingScreen {
    private final ViewGroup parent;
    private final View root;
    private final ProgressBar bar;

    LoadingScreen(ViewGroup parent, Runnable onCancel) {
        this.parent = parent;
        root = LayoutInflater.from(parent.getContext()).inflate(R.layout.loading, parent, false);
        root.setClickable(true); // Alttaki ekrana dokunuşu geçirmez.
        bar = root.findViewById(R.id.loading_bar);
        root.findViewById(R.id.loading_cancel).setOnClickListener(v -> onCancel.run());
        parent.addView(root);
    }

    void setProgress(int percent) { bar.setProgress(Math.max(0, Math.min(100, percent))); }

    void applyInsets(int left, int top, int right, int bottom) { root.setPadding(left, top, right, bottom); }

    void dismiss() { parent.removeView(root); }
}
