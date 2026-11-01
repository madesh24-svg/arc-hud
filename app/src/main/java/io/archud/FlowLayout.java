package io.archud;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children left to right and wraps to a new line when the width runs out. */
final class FlowLayout extends ViewGroup {
    FlowLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int lineHeight = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxWidth) {
                x = 0;
                y += lineHeight;
                lineHeight = 0;
            }
            x += w;
            widest = Math.max(widest, x);
            lineHeight = Math.max(lineHeight, h);
        }
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? widest + getPaddingLeft() + getPaddingRight() : MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, y + lineHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxWidth) {
                x = 0;
                y += lineHeight;
                lineHeight = 0;
            }
            int left = getPaddingLeft() + x + lp.leftMargin;
            int top = getPaddingTop() + y + lp.topMargin;
            child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
            x += w;
            lineHeight = Math.max(lineHeight, h);
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new MarginLayoutParams(p);
    }
}
