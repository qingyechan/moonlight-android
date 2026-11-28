package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import com.limelight.R;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SpinnerDialog implements Runnable, OnCancelListener {
    private final String title;
    private String message;
    private final Activity activity;
    private ProgressDialog progress;
    private Dialog customDialog;
    private TextView customMessageView;
    private LoadingEffectView effectView;
    private final boolean finish;
    private boolean dismissed = false;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.progress = null;
        this.customDialog = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    dialog.dismissInternal();
                }
            }
        }
    }

    private void dismissInternal() {
        dismissed = true;
        if (effectView != null) {
            effectView.stop();
        }
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
        }
        if (customDialog != null && customDialog.isShowing()) {
            customDialog.dismiss();
        }
    }

    public void dismiss()
    {
        // Running again after creation will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        this.message = message;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progress != null) {
                    progress.setMessage(message);
                }
                if (customMessageView != null) {
                    customMessageView.setText(message);
                }
            }
        });
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (progress == null && customDialog == null && !dismissed)
        {
            int style = BridgeUiPrefs.getLoadingStyle(activity);
            if (style == BridgeUiPrefs.LOADING_STYLE_CLASSIC) {
                progress = new ProgressDialog(activity);

                progress.setTitle(title);
                progress.setMessage(message);
                progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                progress.setOnCancelListener(this);

                if (finish) {
                    progress.setCancelable(true);
                    progress.setCanceledOnTouchOutside(false);
                } else {
                    progress.setCancelable(false);
                }

                synchronized (rundownDialogs) {
                    rundownDialogs.add(this);
                    progress.show();
                }
            } else {
                customDialog = buildStyledDialog(style);
                synchronized (rundownDialogs) {
                    rundownDialogs.add(this);
                    customDialog.show();
                }
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this)) {
                    dismissInternal();
                } else {
                    dismissInternal();
                }
            }
        }
    }

    private Dialog buildStyledDialog(final int style) {
        final BridgeUiPrefs.ThemePalette palette = BridgeUiPrefs.getThemePalette(activity);
        final float density = activity.getResources().getDisplayMetrics().density;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setOnCancelListener(this);
        if (finish) {
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(false);
        } else {
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
        }

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = Math.round(24 * density);
        int padV = Math.round(22 * density);
        card.setPadding(padH, padV, padH, padV);
        card.setMinimumWidth(Math.round(272 * density));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(20 * density);
        cardBg.setColor(Color.argb(242,
                Color.red(palette.surfaceColor),
                Color.green(palette.surfaceColor),
                Color.blue(palette.surfaceColor)));
        cardBg.setStroke(Math.max(2, Math.round(1.5f * density)),
                Color.argb(175,
                        Color.red(palette.accentColor),
                        Color.green(palette.accentColor),
                        Color.blue(palette.accentColor)));
        card.setBackground(cardBg);

        effectView = new LoadingEffectView(activity, style, palette.accentColor);
        LinearLayout.LayoutParams effLp = (style == BridgeUiPrefs.LOADING_STYLE_CYBER)
                ? new LinearLayout.LayoutParams(Math.round(216 * density), Math.round(32 * density))
                : new LinearLayout.LayoutParams(Math.round(68 * density), Math.round(68 * density));
        effLp.bottomMargin = Math.round(14 * density);
        effectView.setLayoutParams(effLp);
        card.addView(effectView);

        if (style == BridgeUiPrefs.LOADING_STYLE_STARLIGHT || style == BridgeUiPrefs.LOADING_STYLE_CYBER) {
            TextView badge = new TextView(activity);
            badge.setText(style == BridgeUiPrefs.LOADING_STYLE_STARLIGHT
                    ? R.string.loading_starlight_badge
                    : R.string.loading_cyber_badge);
            badge.setTextColor(palette.accentColor);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            badge.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.bottomMargin = Math.round(6 * density);
            badge.setLayoutParams(bLp);
            card.addView(badge);
        }

        if (title != null && !title.isEmpty()) {
            TextView titleView = new TextView(activity);
            titleView.setText(title);
            titleView.setTextColor(0xFFF8FAFC);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
            titleView.getPaint().setFakeBoldText(true);
            titleView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp.bottomMargin = Math.round(6 * density);
            titleView.setLayoutParams(tLp);
            card.addView(titleView);
        }

        customMessageView = new TextView(activity);
        customMessageView.setText(message != null ? message : "");
        customMessageView.setTextColor(0xFFCBD5E1);
        customMessageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        customMessageView.setGravity(Gravity.CENTER);
        customMessageView.setMaxWidth(Math.round(260 * density));
        card.addView(customMessageView);

        if (finish) {
            TextView cancelBtn = new TextView(activity);
            cancelBtn.setText(R.string.loading_cancel_btn);
            cancelBtn.setTextColor(0xFFE2E8F0);
            cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            cancelBtn.setGravity(Gravity.CENTER);
            cancelBtn.setPadding(Math.round(18 * density), Math.round(7 * density),
                    Math.round(18 * density), Math.round(7 * density));
            cancelBtn.setBackground(BridgeUiPrefs.buildRoundedShape(
                    activity, palette.cardColor, palette.borderColor, 1f, 10f));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = Math.round(16 * density);
            cancelBtn.setLayoutParams(cLp);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.cancel();
                }
            });
            card.addView(cancelBtn);
        }

        dialog.setContentView(card);
        effectView.start();
        return dialog;
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        if (effectView != null) {
            effectView.stop();
        }
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }

    private static final class LoadingEffectView extends View {
        private final int style;
        private final int accentColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator animator;
        private float phase = 0f;

        LoadingEffectView(Context context, int style, int accentColor) {
            super(context);
            this.style = style;
            this.accentColor = accentColor;
        }

        void start() {
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(style == BridgeUiPrefs.LOADING_STYLE_CYBER ? 1400L : 1800L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    phase = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        }

        void stop() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float density = getResources().getDisplayMetrics().density;

            if (style == BridgeUiPrefs.LOADING_STYLE_CYBER) {
                float trackH = 10f * density;
                float top = (h - trackH) * 0.5f;
                rect.set(2f * density, top, w - 2f * density, top + trackH);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x33FFFFFF);
                canvas.drawRoundRect(rect, trackH * 0.5f, trackH * 0.5f, paint);

                float barW = (w - 4f * density) * 0.36f;
                float travel = (w - 4f * density) - barW;
                float pingPong = phase < 0.5f ? (phase * 2f) : ((1f - phase) * 2f);
                float left = 2f * density + travel * pingPong;
                rect.set(left, top, left + barW, top + trackH);
                paint.setColor(accentColor);
                canvas.drawRoundRect(rect, trackH * 0.5f, trackH * 0.5f, paint);
                return;
            }

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float outerR = Math.min(w, h) * 0.42f;
            float angle = phase * 360f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(3.2f * density);
            paint.setColor(Color.argb(50, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            canvas.drawCircle(cx, cy, outerR, paint);

            rect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
            paint.setColor(accentColor);
            canvas.drawArc(rect, angle - 90f, 115f, false, paint);

            if (style == BridgeUiPrefs.LOADING_STYLE_STARLIGHT) {
                float innerR = outerR * 0.65f;
                rect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
                paint.setStrokeWidth(2.2f * density);
                paint.setColor(Color.argb(200, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
                canvas.drawArc(rect, -angle * 1.4f, 95f, false, paint);
                canvas.drawArc(rect, -angle * 1.4f + 180f, 95f, false, paint);

                float pulse = 0.72f + 0.28f * (float) Math.sin(phase * Math.PI * 2.0);
                float starR = outerR * 0.28f * pulse;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFF8FAFC);
                canvas.drawCircle(cx, cy, starR * 0.55f, paint);
                paint.setStrokeWidth(1.8f * density);
                paint.setColor(accentColor);
                canvas.drawLine(cx - starR, cy, cx + starR, cy, paint);
                canvas.drawLine(cx, cy - starR, cx, cy + starR, paint);
            }
        }
    }
}
