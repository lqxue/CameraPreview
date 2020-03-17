package com.camera.preview.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

public class ShowRectView extends View {

    private Paint mpaint;
    private List<Rect> rect;

    public ShowRectView(Context context) {
        this(context, null);
    }

    public ShowRectView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ShowRectView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        //定义画笔
        mpaint = new Paint();
        mpaint.setColor(Color.BLUE);
        // mpaint.setAntiAlias(true);//去锯齿
        mpaint.setStyle(Paint.Style.STROKE);//空心
        // 设置paint的外框宽度
        mpaint.setStrokeWidth(6f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rect != null && rect.size() > 0) {
            for (int i = 0; i < rect.size(); i++) {
                canvas.drawRect(rect.get(i), mpaint);
            }
        }
    }

    public void setRect(List<Rect> rect) {
        this.rect = rect;
        invalidate();
    }
}
