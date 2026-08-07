package dev.butschster.linuxvr.term;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Buttons that wrap, so the window's size decides how many are visible.
 *
 * <p>The first version of the bar scrolled sideways: a fixed strip, and everything past
 * its right edge had to be dragged into view. In the headset the window is resizable and
 * the space is free, so wrapping is strictly better — make the window wider and more
 * buttons fit per row, make it taller and more rows show at once.
 *
 * <p>Android has no wrapping container outside of androidx, and pulling in flexbox for
 * sixty lines of measure-and-place would be a dependency for its own sake.
 *
 * <p>A child tagged {@link #BREAK} starts a new row. That is how groups stay visually
 * separate without a divider that wrapping would put in the wrong place anyway.
 */
public class FlowLayout extends ViewGroup {

    public static final String BREAK = "break";

    private final int gap;

    public FlowLayout(Context context, int gap) {
        super(context);
        this.gap = gap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        final int childSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);

        int x = 0;
        int rowHeight = 0;
        int totalHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

            boolean forced = BREAK.equals(child.getTag());
            if (x > 0 && (forced || x + child.getMeasuredWidth() > available)) {
                totalHeight += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            x += child.getMeasuredWidth() + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        totalHeight += rowHeight;

        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int available = right - left - getPaddingLeft() - getPaddingRight();

        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            boolean forced = BREAK.equals(child.getTag());
            if (x > getPaddingLeft()
                    && (forced || x + child.getMeasuredWidth() > available + getPaddingLeft())) {
                x = getPaddingLeft();
                y += rowHeight + gap;
                rowHeight = 0;
            }
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            x += child.getMeasuredWidth() + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
    }
}
