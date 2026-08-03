package dora.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Scroller
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * 横幅轮播控件。
 *
 * 支持以下功能：
 *
 * - 多个横幅页面循环轮播
 * - 自动播放
 * - 手指左右滑动
 * - 点击事件
 * - 当前页面指示器
 * - 自定义轮播间隔
 * - 自定义动画持续时间
 * - 支持 Drawable / DrawableRes
 * - 支持自定义 View
 * - 支持保存当前页面状态
 *
 * 图片网络加载不由本控件负责。
 * 可以在外部使用 Glide、Coil、Picasso 等图片加载框架，
 * 加载完成后通过 [setItems] 或 [setViews] 设置到控件中。
 */
class DoraBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * Banner 数据项。
     */
    private val items = ArrayList<BannerItem>()

    /**
     * 当前页面下标。
     */
    private var currentItem = 0

    /**
     * 当前实际显示的页面。
     *
     * 因为使用首尾复制页面实现无限循环，所以：
     *
     * 0 -> 最后一个页面
     * 1 -> 第一个真实页面
     * 2 -> 第二个真实页面
     */
    private var currentPage = 0

    /**
     * 自动轮播任务。
     */
    private val autoPlayRunnable = Runnable {
        if (isAutoPlayEnabled && items.size > 1) {
            smoothScrollToPage(currentPage + 1)
        }
    }

    /**
     * 是否自动播放。
     */
    private var isAutoPlayEnabled = true

    /**
     * 自动播放间隔。
     */
    private var autoPlayInterval: Long = DEFAULT_INTERVAL

    /**
     * 动画持续时间。
     */
    private var scrollDuration: Long = DEFAULT_DURATION

    /**
     * 是否循环。
     */
    private var loopEnabled = true

    /**
     * 是否显示指示器。
     */
    private var indicatorVisible = true

    /**
     * 指示器圆点半径。
     */
    private var indicatorRadius = dp2px(4f).toFloat()

    /**
     * 指示器间距。
     */
    private var indicatorSpace = dp2px(8f).toFloat()

    /**
     * 指示器距离底部的距离。
     */
    private var indicatorBottomMargin = dp2px(12f)

    /**
     * 未选中指示器颜色。
     */
    private var indicatorNormalColor = 0x66FFFFFF

    /**
     * 选中指示器颜色。
     */
    private var indicatorSelectedColor = 0xFFFFFFFF.toInt()

    /**
     * 指示器画笔。
     */
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 页面滚动器。
     */
    private val scroller = Scroller(
        context,
        DecelerateInterpolator()
    )

    /**
     * 最小滑动距离。
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 最大速度。
     */
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /**
     * 最小速度。
     */
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /**
     * 速度追踪器。
     */
    private var velocityTracker: VelocityTracker? = null

    /**
     * 手指按下时的 X 坐标。
     */
    private var downX = 0f

    /**
     * 手指按下时的 Y 坐标。
     */
    private var downY = 0f

    /**
     * 上一次触摸 X 坐标。
     */
    private var lastX = 0f

    /**
     * 是否正在拖动。
     */
    private var dragging = false

    /**
     * 是否发生过移动。
     */
    private var moved = false

    /**
     * Banner 点击回调。
     */
    private var onBannerClickListener: OnBannerClickListener? = null

    /**
     * Banner 页面切换回调。
     */
    private var onPageChangedListener: OnPageChangedListener? = null

    init {
        setWillNotDraw(false)

        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.DoraBannerView,
            defStyleAttr,
            0
        )

        isAutoPlayEnabled = typedArray.getBoolean(
            R.styleable.DoraBannerView_dview_bv_autoPlay,
            true
        )

        autoPlayInterval = typedArray.getInt(
            R.styleable.DoraBannerView_dview_bv_interval,
            DEFAULT_INTERVAL.toInt()
        ).coerceAtLeast(MIN_INTERVAL.toInt()).toLong()

        scrollDuration = typedArray.getInt(
            R.styleable.DoraBannerView_dview_bv_duration,
            DEFAULT_DURATION.toInt()
        ).coerceAtLeast(0).toLong()

        loopEnabled = typedArray.getBoolean(
            R.styleable.DoraBannerView_dview_bv_loop,
            true
        )

        indicatorVisible = typedArray.getBoolean(
            R.styleable.DoraBannerView_dview_bv_indicatorVisible,
            true
        )

        indicatorRadius = typedArray.getDimension(
            R.styleable.DoraBannerView_dview_bv_indicatorRadius,
            indicatorRadius
        )

        indicatorSpace = typedArray.getDimension(
            R.styleable.DoraBannerView_dview_bv_indicatorSpace,
            indicatorSpace
        )

        indicatorBottomMargin = typedArray.getDimensionPixelSize(
            R.styleable.DoraBannerView_dview_bv_indicatorBottomMargin,
            indicatorBottomMargin
        )

        indicatorNormalColor = typedArray.getColor(
            R.styleable.DoraBannerView_dview_bv_indicatorNormalColor,
            indicatorNormalColor
        )

        indicatorSelectedColor = typedArray.getColor(
            R.styleable.DoraBannerView_dview_bv_indicatorSelectedColor,
            indicatorSelectedColor
        )

        typedArray.recycle()
    }

    /**
     * 设置 Drawable Banner。
     *
     * @param drawables Banner 图片。
     */
    fun setItems(vararg drawables: Drawable) {
        setItems(drawables.toList())
    }

    /**
     * 设置 Drawable Banner。
     *
     * @param drawables Banner 图片。
     */
    fun setItems(drawables: List<Drawable>) {
        removeAllViews()
        items.clear()

        drawables.forEach {
            items.add(BannerItem.DrawableItem(it))
        }

        rebuildViews()
    }

    /**
     * 设置 DrawableRes Banner。
     *
     * @param drawableResIds Drawable 资源 ID。
     */
    fun setItems(@DrawableRes vararg drawableResIds: Int) {
        val drawables = ArrayList<Drawable>()
        for (drawableResId in drawableResIds) {
            val drawable = ContextCompat.getDrawable(context, drawableResId)
            if (drawable != null) {
                drawables.add(drawable)
            }
        }
        setItems(drawables)
    }

    /**
     * 设置自定义 Banner View。
     *
     * 每个 View 对应一个 Banner 页面。
     *
     * @param views Banner View。
     */
    fun setViews(vararg views: View) {
        setViews(views.toList())
    }

    /**
     * 设置自定义 Banner View。
     *
     * @param views Banner View。
     */
    fun setViews(views: List<View>) {
        removeAllViews()
        items.clear()

        views.forEach {
            items.add(BannerItem.ViewItem(it))
        }

        rebuildViews()
    }

    /**
     * 添加 Drawable Banner。
     *
     * @param drawable Banner 图片。
     */
    fun addBanner(drawable: Drawable) {
        items.add(BannerItem.DrawableItem(drawable))
        rebuildViews()
    }

    /**
     * 添加 DrawableRes Banner。
     *
     * @param drawableResId Drawable 资源 ID。
     */
    fun addBanner(@DrawableRes drawableResId: Int) {
        val drawable = ContextCompat.getDrawable(context, drawableResId)
            ?: return

        addBanner(drawable)
    }

    /**
     * 添加自定义 Banner View。
     *
     * @param view Banner View。
     */
    fun addBanner(view: View) {
        items.add(BannerItem.ViewItem(view))
        rebuildViews()
    }

    /**
     * 清空所有 Banner。
     */
    fun clearItems() {
        stopAutoPlay()
        removeAllViews()
        items.clear()
        currentItem = 0
        currentPage = 0
        scrollTo(0, 0)
        invalidate()
    }

    /**
     * 设置是否自动播放。
     *
     * @param enabled true 表示开启。
     */
    fun setAutoPlay(enabled: Boolean) {
        isAutoPlayEnabled = enabled

        if (enabled) {
            startAutoPlay()
        } else {
            stopAutoPlay()
        }
    }

    /**
     * 设置自动轮播间隔。
     *
     * 单位：毫秒。
     *
     * @param interval 间隔时间。
     */
    fun setAutoPlayInterval(interval: Long) {
        autoPlayInterval = interval.coerceAtLeast(MIN_INTERVAL)

        if (isAutoPlayEnabled) {
            stopAutoPlay()
            startAutoPlay()
        }
    }

    /**
     * 设置轮播动画持续时间。
     *
     * 单位：毫秒。
     *
     * @param duration 动画持续时间。
     */
    fun setScrollDuration(duration: Long) {
        scrollDuration = duration.coerceAtLeast(0)
    }

    /**
     * 设置是否循环。
     *
     * @param enabled true 表示循环。
     */
    fun setLoopEnabled(enabled: Boolean) {
        loopEnabled = enabled

        if (!enabled) {
            currentPage = currentItem
            requestLayout()
        } else {
            rebuildViews()
        }
    }

    /**
     * 设置是否显示指示器。
     *
     * @param visible true 表示显示。
     */
    fun setIndicatorVisible(visible: Boolean) {
        indicatorVisible = visible
        invalidate()
    }

    /**
     * 设置当前页面。
     *
     * @param index 页面下标，从 0 开始。
     * @param smoothScroll 是否使用动画。
     */
    fun setCurrentItem(
        index: Int,
        smoothScroll: Boolean = true
    ) {
        if (items.isEmpty()) {
            return
        }

        val target = index.coerceIn(0, items.lastIndex)

        currentItem = target

        if (loopEnabled && items.size > 1) {
            currentPage = target + 1
        } else {
            currentPage = target
        }

        if (smoothScroll) {
            smoothScrollToPage(currentPage)
        } else {
            scrollTo(currentPage * width, 0)
            notifyPageChanged()
            invalidate()
        }
    }

    /**
     * 获取当前页面。
     *
     * @return 当前页面下标。
     */
    fun getCurrentItem(): Int {
        return currentItem
    }

    /**
     * 获取 Banner 数量。
     *
     * @return Banner 数量。
     */
    fun getItemCount(): Int {
        return items.size
    }

    /**
     * 开始自动轮播。
     */
    fun startAutoPlay() {
        if (!isAttachedToWindow) {
            return
        }

        if (!isAutoPlayEnabled || items.size <= 1) {
            return
        }

        removeCallbacks(autoPlayRunnable)
        postDelayed(autoPlayRunnable, autoPlayInterval)
    }

    /**
     * 停止自动轮播。
     */
    fun stopAutoPlay() {
        removeCallbacks(autoPlayRunnable)
    }

    /**
     * 设置 Banner 点击监听。
     *
     * @param listener 点击监听器。
     */
    fun setOnBannerClickListener(
        listener: OnBannerClickListener?
    ) {
        onBannerClickListener = listener
    }

    /**
     * 设置页面切换监听。
     *
     * @param listener 页面切换监听器。
     */
    fun setOnPageChangedListener(
        listener: OnPageChangedListener?
    ) {
        onPageChangedListener = listener
    }

    /**
     * 重新构建 Banner 页面。
     */
    private fun rebuildViews() {
        stopAutoPlay()
        removeAllViews()

        if (items.isEmpty()) {
            currentItem = 0
            currentPage = 0
            invalidate()
            return
        }

        if (loopEnabled && items.size > 1) {
            addBannerView(items.lastIndex)
        }

        items.forEachIndexed { index, _ ->
            addBannerView(index)
        }

        if (loopEnabled && items.size > 1) {
            addBannerView(0)
            currentPage = currentItem + 1
        } else {
            currentPage = currentItem
        }

        requestLayout()

        post {
            scrollTo(currentPage * width, 0)
            startAutoPlay()
        }
    }

    /**
     * 添加指定 Banner 页面。
     */
    private fun addBannerView(index: Int) {
        val item = items[index]

        val child = when (item) {
            is BannerItem.DrawableItem -> {
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(item.drawable)
                }
            }

            is BannerItem.ViewItem -> {
                item.view
            }
        }

        child.setOnClickListener {
            if (!moved) {
                onBannerClickListener?.onBannerClick(
                    this,
                    currentItem
                )
            }
        }

        addView(child)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val width = resolveSize(
            suggestedMinimumWidth,
            widthMeasureSpec
        )

        val height = resolveBannerHeight(heightMeasureSpec)

        setMeasuredDimension(width, height)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(
            width,
            MeasureSpec.EXACTLY
        )

        val childHeightSpec = MeasureSpec.makeMeasureSpec(
            height,
            MeasureSpec.EXACTLY
        )

        for (i in 0 until childCount) {
            getChildAt(i).measure(
                childWidthSpec,
                childHeightSpec
            )
        }
    }

    /**
     * 计算 Banner 高度。
     */
    private fun resolveBannerHeight(
        heightMeasureSpec: Int
    ): Int {
        return when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> {
                MeasureSpec.getSize(heightMeasureSpec)
            }

            MeasureSpec.AT_MOST -> {
                MeasureSpec.getSize(heightMeasureSpec)
            }

            else -> {
                suggestedMinimumHeight
            }
        }
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val childWidth = width

        for (i in 0 until childCount) {
            val child = getChildAt(i)

            val childLeft = i * childWidth

            child.layout(
                childLeft,
                0,
                childLeft + childWidth,
                height
            )
        }

        if (changed) {
            scrollTo(currentPage * width, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (indicatorVisible && items.size > 1) {
            drawIndicator(canvas)
        }
    }

    /**
     * 绘制页面指示器。
     */
    private fun drawIndicator(canvas: Canvas) {
        val count = items.size

        val totalWidth =
            count * indicatorRadius * 2 +
                (count - 1) * indicatorSpace

        var startX = (width - totalWidth) / 2f

        val centerY =
            height - indicatorBottomMargin - indicatorRadius

        for (i in 0 until count) {
            indicatorPaint.color =
                if (i == currentItem) {
                    indicatorSelectedColor
                } else {
                    indicatorNormalColor
                }

            canvas.drawCircle(
                startX + indicatorRadius,
                centerY,
                indicatorRadius,
                indicatorPaint
            )

            startX += indicatorRadius * 2 + indicatorSpace
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.size <= 1) {
            return super.onTouchEvent(event)
        }

        ensureVelocityTracker()

        velocityTracker?.addMovement(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                stopAutoPlay()

                scroller.abortAnimation()

                downX = event.x
                downY = event.y
                lastX = event.x

                dragging = false
                moved = false

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = event.x - downX
                val totalDy = event.y - downY

                if (!dragging) {
                    if (
                        abs(totalDx) > touchSlop &&
                        abs(totalDx) > abs(totalDy)
                    ) {
                        dragging = true
                        moved = true
                    }
                }

                if (dragging) {
                    scrollBy(
                        (-dx).toInt(),
                        0
                    )

                    limitScrollRange()
                }

                lastX = event.x

                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    handleRelease()
                } else {
                    startAutoPlay()
                }

                recycleVelocityTracker()

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    settleToNearestPage()
                }

                recycleVelocityTracker()

                parent?.requestDisallowInterceptTouchEvent(false)

                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 处理手指释放。
     */
    private fun handleRelease() {
        velocityTracker?.computeCurrentVelocity(
            1000,
            maximumVelocity.toFloat()
        )

        val velocityX = velocityTracker?.xVelocity ?: 0f

        val currentScroll = scrollX
        val pageWidth = width

        if (pageWidth <= 0) {
            return
        }

        val currentPageFloat =
            currentScroll.toFloat() / pageWidth

        var targetPage =
            currentPageFloat.toInt()

        val offset =
            currentPageFloat - targetPage

        if (
            abs(velocityX) >= minimumVelocity
        ) {
            targetPage = if (velocityX < 0) {
                targetPage + 1
            } else {
                targetPage
            }
        } else {
            if (offset >= 0.5f) {
                targetPage++
            }
        }

        targetPage = targetPage.coerceIn(
            0,
            childCount - 1
        )

        smoothScrollToPage(targetPage)
    }

    /**
     * 回到最近页面。
     */
    private fun settleToNearestPage() {
        if (width <= 0) {
            return
        }

        val targetPage =
            ((scrollX.toFloat() / width) + 0.5f)
                .toInt()
                .coerceIn(0, childCount - 1)

        smoothScrollToPage(targetPage)
    }

    /**
     * 平滑滚动到指定页面。
     */
    private fun smoothScrollToPage(page: Int) {
        if (width <= 0 || childCount == 0) {
            return
        }

        val targetPage =
            page.coerceIn(0, childCount - 1)

        val targetX = targetPage * width

        val dx = targetX - scrollX

        if (dx == 0) {
            finishScroll(targetPage)
            return
        }

        scroller.startScroll(
            scrollX,
            0,
            dx,
            0,
            scrollDuration.toInt()
        )

        invalidate()
    }

    /**
     * 限制手势滚动范围。
     */
    private fun limitScrollRange() {
        val maxScroll =
            max(0, (childCount - 1) * width)

        if (scrollX < 0) {
            scrollTo(0, 0)
        } else if (scrollX > maxScroll) {
            scrollTo(maxScroll, 0)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(
                scroller.currX,
                scroller.currY
            )

            invalidate()
            return
        }

        if (childCount > 0 && width > 0) {
            val page =
                (scrollX.toFloat() / width)
                    .toInt()

            if (
                scrollX == page * width
            ) {
                finishScroll(page)
            }
        }
    }

    /**
     * 完成一次页面滚动。
     */
    private fun finishScroll(page: Int) {
        if (items.isEmpty()) {
            return
        }

        if (loopEnabled && items.size > 1) {

            when {
                page == 0 -> {
                    currentItem = items.lastIndex
                    currentPage = items.size

                    scrollTo(
                        currentPage * width,
                        0
                    )

                    notifyPageChanged()
                }

                page == childCount - 1 -> {
                    currentItem = 0
                    currentPage = 1

                    scrollTo(
                        currentPage * width,
                        0
                    )

                    notifyPageChanged()
                }

                else -> {
                    val newItem = page - 1

                    if (newItem != currentItem) {
                        currentItem = newItem
                        currentPage = page
                        notifyPageChanged()
                    }
                }
            }

        } else {
            val newItem =
                page.coerceIn(0, items.lastIndex)

            if (newItem != currentItem) {
                currentItem = newItem
                currentPage = newItem
                notifyPageChanged()
            }
        }

        invalidate()
        startAutoPlay()
    }

    /**
     * 通知页面发生变化。
     */
    private fun notifyPageChanged() {
        onPageChangedListener?.onPageChanged(
            this,
            currentItem
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAutoPlay()
    }

    override fun onDetachedFromWindow() {
        stopAutoPlay()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()

        bundle.putParcelable(
            KEY_SUPER_STATE,
            super.onSaveInstanceState()
        )

        bundle.putInt(
            KEY_CURRENT_ITEM,
            currentItem
        )

        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            currentItem = state.getInt(
                KEY_CURRENT_ITEM,
                0
            )

            val superState =
                state.getParcelable<Parcelable>(
                    KEY_SUPER_STATE
                )

            super.onRestoreInstanceState(superState)
            return
        }

        super.onRestoreInstanceState(state)
    }

    /**
     * 创建速度追踪器。
     */
    private fun ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
    }

    /**
     * 回收速度追踪器。
     */
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * dp 转 px。
     */
    private fun dp2px(dpVal: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpVal,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Banner 数据项。
     */
    private sealed class BannerItem {

        /**
         * Drawable 类型 Banner。
         */
        class DrawableItem(
            val drawable: Drawable
        ) : BannerItem()

        /**
         * 自定义 View 类型 Banner。
         */
        class ViewItem(
            val view: View
        ) : BannerItem()
    }

    /**
     * Banner 点击监听器。
     */
    fun interface OnBannerClickListener {

        /**
         * Banner 点击回调。
         *
         * @param view Banner 控件。
         * @param position 当前 Banner 下标。
         */
        fun onBannerClick(
            view: DoraBannerView,
            position: Int
        )
    }

    /**
     * Banner 页面变化监听器。
     */
    fun interface OnPageChangedListener {

        /**
         * 页面变化回调。
         *
         * @param view Banner 控件。
         * @param position 当前 Banner 下标。
         */
        fun onPageChanged(
            view: DoraBannerView,
            position: Int
        )
    }

    companion object {

        /**
         * 默认自动播放间隔。
         */
        private const val DEFAULT_INTERVAL = 3000L

        /**
         * 默认滑动动画持续时间。
         */
        private const val DEFAULT_DURATION = 300L

        /**
         * 最小自动播放间隔。
         */
        private const val MIN_INTERVAL = 500L

        /**
         * 状态保存 Key。
         */
        private const val KEY_SUPER_STATE =
            "super_state"

        /**
         * 当前页面状态 Key。
         */
        private const val KEY_CURRENT_ITEM =
            "current_item"
    }
}