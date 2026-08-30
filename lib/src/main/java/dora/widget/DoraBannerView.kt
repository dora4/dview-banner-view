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
import androidx.core.content.withStyledAttributes
import dora.widget.banner.BannerAdapter
import dora.widget.banner.R
import kotlin.math.abs
import kotlin.math.max
import androidx.core.view.isNotEmpty

/**
 * 横幅轮播控件。
 *
 * 支持：
 *
 * - BannerAdapter<T>
 * - Drawable
 * - 自定义 View
 * - 无限循环
 * - 自动播放
 * - 手指左右滑动
 * - 点击事件
 * - 页面滚动监听
 * - 内置 Indicator
 * - 保存 / 恢复当前页面
 */
class DoraBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * Banner Adapter。
     */
    private var adapter: BannerAdapter<*, *>? = null

    /**
     * 当前真实数据页面。
     *
     * 例如：
     *
     * 真实数据：
     * 0 1 2
     *
     * 无限循环实际页面：
     * 2 0 1 2 0
     *
     * currentItem 始终保存真实数据位置。
     */
    private var currentItem = 0

    /**
     * 当前虚拟页面。
     */
    private var currentPage = 0

    /**
     * 自动播放任务。
     */
    private val autoPlayRunnable = Runnable {
        if (!isAttachedToWindow) {
            return@Runnable
        }
        if (!isAutoPlayEnabled || getItemCount() <= 1) {
            return@Runnable
        }
        if (width <= 0 || childCount <= 0) {
            startAutoPlay()
            return@Runnable
        }
        // 如果之前的动画还没有结束，不重复启动。
        if (scroller.isFinished.not()) {
            startAutoPlay()
            return@Runnable
        }
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(currentPage + 1)
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
     * 滑动动画持续时间。
     */
    private var scrollDuration: Long = DEFAULT_DURATION

    /**
     * 是否循环。
     */
    private var loopEnabled = true

    /**
     * 当前滚动状态。
     */
    private var scrollState = SCROLL_STATE_IDLE

    /**
     * 页面监听器。
     */
    private val pageChangeListeners = ArrayList<OnPageChangeListener>()

    /**
     * Banner 点击监听器。
     */
    private var onBannerClickListener: OnBannerClickListener? = null

    /**
     * 是否显示内部 Indicator。
     */
    private var indicatorVisible = false

    /**
     * Indicator 圆点半径。
     */
    private var indicatorRadius = dp2px(4f).toFloat()

    /**
     * Indicator 圆点间距。
     */
    private var indicatorSpace = dp2px(8f).toFloat()

    /**
     * Indicator 距离底部距离。
     */
    private var indicatorBottomMargin = dp2px(12f)

    /**
     * 未选中颜色。
     */
    private var indicatorNormalColor = 0x66FFFFFF

    /**
     * 选中颜色。
     */
    private var indicatorSelectedColor = 0xFFFFFFFF.toInt()

    /**
     * Indicator 画笔。
     */
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 页面滚动器。
     */
    private val scroller = Scroller(context, DecelerateInterpolator())

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
     * 手指按下 X。
     */
    private var downX = 0f

    /**
     * 手指按下 Y。
     */
    private var downY = 0f

    /**
     * 上一次 X。
     */
    private var lastX = 0f

    /**
     * 是否正在拖动。
     */
    private var dragging = false

    /**
     * 是否发生移动。
     */
    private var moved = false

    init {
        setWillNotDraw(false)
        context.withStyledAttributes(
            attrs,
            R.styleable.DoraBannerView,
            defStyleAttr,
            0
        ) {
            isAutoPlayEnabled = getBoolean(
                R.styleable.DoraBannerView_dview_bv_autoPlay,
                true
            )
            autoPlayInterval = getInt(
                R.styleable.DoraBannerView_dview_bv_interval,
                DEFAULT_INTERVAL.toInt()
            )
                .coerceAtLeast(
                    MIN_INTERVAL.toInt()
                )
                .toLong()
            scrollDuration = getInt(
                R.styleable.DoraBannerView_dview_bv_duration,
                DEFAULT_DURATION.toInt()
            )
                .coerceAtLeast(0)
                .toLong()
            loopEnabled = getBoolean(
                R.styleable.DoraBannerView_dview_bv_loop,
                true
            )
            indicatorVisible = getBoolean(
                R.styleable.DoraBannerView_dview_bv_indicatorVisible,
                false
            )
            indicatorRadius = getDimension(
                R.styleable.DoraBannerView_dview_bv_indicatorRadius,
                indicatorRadius
            )
            indicatorSpace = getDimension(
                R.styleable.DoraBannerView_dview_bv_indicatorSpace,
                indicatorSpace
            )
            indicatorBottomMargin = getDimensionPixelSize(
                R.styleable.DoraBannerView_dview_bv_indicatorBottomMargin,
                indicatorBottomMargin
            )
            indicatorNormalColor = getColor(
                R.styleable.DoraBannerView_dview_bv_indicatorNormalColor,
                indicatorNormalColor
            )
            indicatorSelectedColor = getColor(
                R.styleable.DoraBannerView_dview_bv_indicatorSelectedColor,
                indicatorSelectedColor
            )
        }
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    /**
     * 设置 Banner Adapter。
     *
     * 示例：
     *
     * banner.setAdapter(object : BannerAdapter<Banner>() {
     *
     *     override fun getItemCount(): Int {
     *         return data.size
     *     }
     *
     *     override fun onCreateView(context: Context): View {
     *         return ImageView(context)
     *     }
     *
     *     override fun onBindView(
     *         view: View,
     *         position: Int
     *     ) {
     *         ...
     *     }
     * })
     */
    fun setAdapter(adapter: BannerAdapter<*, *>) {
        stopAutoPlay()
        this.adapter?.onDataSetChangedListener = null
        this.adapter = adapter
        adapter.onDataSetChangedListener = {
            post {
                rebuildAdapterViews()
            }
        }
        currentItem = currentItem.coerceIn(0, max(0, getItemCount() - 1))
        rebuildAdapterViews()
    }

    /**
     * 获取当前 Adapter。
     */
    fun getAdapter(): BannerAdapter<*, *>? {
        return adapter
    }

    /**
     * Adapter 数据发生变化时重新构建。
     */
    private fun rebuildAdapterViews() {
        stopAutoPlay()
        removeAllViews()
        val count = getItemCount()
        if (count <= 0) {
            currentItem = 0
            currentPage = 0
            scrollTo(0, 0)
            invalidate()
            return
        }
        currentItem = currentItem.coerceIn(0, count - 1)
        if (loopEnabled && count > 1) {
            // 首部添加最后一个虚拟页面
            addAdapterView(count - 1)
        }
        for (position in 0 until count) {
            addAdapterView(position)
        }
        if (loopEnabled && count > 1) {
            // 尾部添加第一个虚拟页面
            addAdapterView(0)
            currentPage = currentItem + 1
        } else {
            currentPage = currentItem
        }
        requestLayout()
        post {
            if (width > 0) {
                scrollTo(
                    currentPage * width,
                    0
                )
            }
            dispatchPageSelected()
            dispatchPageScrolled()
            startAutoPlay()
        }
    }

    /**
     * 创建并绑定 Adapter View。
     *
     * @param position 真实数据位置。
     */
    private fun addAdapterView(position: Int) {
        val currentAdapter = adapter ?: return
        val child = currentAdapter.onCreateView(context)
        val item = currentAdapter.getItem(position) ?: return
        @Suppress("UNCHECKED_CAST")
        (currentAdapter as BannerAdapter<Any, View>).onBindView(
            child,
            item,
            position
        )
        addView(child)
    }

    // -------------------------------------------------------------------------
    // Drawable / View API
    // -------------------------------------------------------------------------

    /**
     * 设置 Drawable Banner。
     */
    fun setItems(vararg drawables: Drawable) {
        setItems(drawables.toList())
    }

    /**
     * 设置 Drawable Banner。
     */
    fun setItems(drawables: List<Drawable>) {
        setAdapter(
            object : BannerAdapter<Drawable, View>() {

                override fun getItemCount(): Int {
                    return drawables.size
                }

                override fun onCreateView(
                    context: Context
                ): View {
                    return ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                }

                override fun onBindView(
                    view: View,
                    model: Drawable,
                    position: Int
                ) {
                    (view as ImageView).setImageDrawable(drawables[position])
                }

                override fun getItem(
                    position: Int
                ): Drawable? {

                    return drawables.getOrNull(
                        position
                    )
                }
            }
        )
    }

    /**
     * 设置 DrawableRes Banner。
     */
    fun setItems(
        @DrawableRes vararg drawableResIds: Int
    ) {
        val drawables = ArrayList<Drawable>()
        drawableResIds.forEach { resId ->
            ContextCompat.getDrawable(context, resId)
                ?.let {
                    drawables.add(it)
                }
        }
        setItems(drawables)
    }

    /**
     * 设置自定义 View Banner。
     */
    fun setViews(vararg views: View) {
        setViews(views.toList())
    }

    /**
     * 设置自定义 View Banner。
     */
    fun setViews(views: List<View>) {
        setAdapter(
            object : BannerAdapter<View, View>() {

                override fun getItemCount(): Int {
                    return views.size
                }

                override fun onCreateView(context: Context): View {
                    return views.firstOrNull() ?: View(context)
                }

                override fun onBindView(
                    view: View,
                    model: View,
                    position: Int
                ) {

                    /*
                     * 这里仅用于兼容原来的 setViews API。
                     *
                     * 实际使用 BannerAdapter 时，
                     * 推荐直接使用 Adapter 创建 View。
                     */
                }

                override fun getItem(position: Int): View? {
                    return views.getOrNull(position)
                }
            }
        )
    }

    /**
     * 添加 Drawable Banner。
     */
    fun addBanner(drawable: Drawable) {
        val currentAdapter = adapter
        if (currentAdapter == null || currentAdapter !is SimpleDrawableAdapter) {
            val list = ArrayList<Drawable>()
            for (i in 0 until getItemCount()) {
                val item = currentAdapter?.getItem(i)
                if (item is Drawable) {
                    list.add(item)
                }
            }
            list.add(drawable)
            setItems(list)
        }
    }

    /**
     * 添加 DrawableRes Banner。
     */
    fun addBanner(@DrawableRes drawableResId: Int) {
        ContextCompat.getDrawable(context, drawableResId)
            ?.let {
                addBanner(it)
            }
    }

    /**
     * 添加 View Banner。
     */
    fun addBanner(view: View) {
        val views = ArrayList<View>()
        val currentAdapter = adapter
        if (currentAdapter != null) {
            for (i in 0 until getItemCount()) {
                currentAdapter
                    .getItem(i)
                    ?.let {
                        if (it is View) {
                            views.add(it)
                        }
                    }
            }
        }
        views.add(view)
        setViews(views)
    }

    /**
     * 清空 Banner。
     */
    fun clearItems() {
        stopAutoPlay()
        adapter?.onDataSetChangedListener = null
        adapter = null
        removeAllViews()
        currentItem = 0
        currentPage = 0
        scrollTo(0, 0)
        dispatchScrollStateChanged(SCROLL_STATE_IDLE)
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Basic API
    // -------------------------------------------------------------------------

    /**
     * 获取 Banner 数量。
     */
    fun getItemCount(): Int {
        return adapter?.getItemCount() ?: 0
    }

    /**
     * 获取当前真实页面。
     */
    fun getCurrentItem(): Int {
        return currentItem
    }

    /**
     * 设置当前页面。
     */
    fun setCurrentItem(
        index: Int,
        smoothScroll: Boolean = true
    ) {
        val count = getItemCount()
        if (count <= 0) {
            return
        }
        val target =
            index.coerceIn(
                0,
                count - 1
            )
        currentItem = target
        currentPage =
            if (loopEnabled && count > 1) {
                target + 1
            } else {
                target
            }
        if (smoothScroll) {
            dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
            smoothScrollToPage(currentPage)
        } else {
            scrollTo(currentPage * width, 0)
            dispatchPageSelected()
            dispatchPageScrolled()
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            invalidate()
        }
    }

    // -------------------------------------------------------------------------
    // Auto Play
    // -------------------------------------------------------------------------

    /**
     * 设置自动播放。
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
     * 设置自动播放间隔。
     */
    fun setAutoPlayInterval(interval: Long) {
        autoPlayInterval = interval.coerceAtLeast(MIN_INTERVAL)
        if (isAutoPlayEnabled) {
            stopAutoPlay()
            startAutoPlay()
        }
    }

    /**
     * 设置滑动动画时间。
     */
    fun setScrollDuration(duration: Long) {
        scrollDuration = duration.coerceAtLeast(0)
    }

    /**
     * 开始自动播放。
     */
    fun startAutoPlay() {
        if (!isAttachedToWindow) {
            return
        }
        if (!isAutoPlayEnabled || getItemCount() <= 1) {
            return
        }
        if (width <= 0 || childCount <= 0) {
            post {
                startAutoPlay()
            }
            return
        }
        removeCallbacks(autoPlayRunnable)
        postDelayed(
            autoPlayRunnable,
            autoPlayInterval
        )
    }

    /**
     * 停止自动播放。
     */
    fun stopAutoPlay() {
        removeCallbacks(autoPlayRunnable)
    }

    // -------------------------------------------------------------------------
    // Loop
    // -------------------------------------------------------------------------

    /**
     * 设置是否循环。
     */
    fun setLoopEnabled(enabled: Boolean) {
        if (loopEnabled == enabled) {
            return
        }
        loopEnabled = enabled
        rebuildAdapterViews()
    }

    // -------------------------------------------------------------------------
    // Indicator
    // -------------------------------------------------------------------------

    /**
     * 设置内部 Indicator 是否显示。
     */
    fun setIndicatorVisible(visible: Boolean) {
        indicatorVisible = visible
        invalidate()
    }

    /**
     * 是否显示内部 Indicator。
     */
    fun isIndicatorVisible(): Boolean {
        return indicatorVisible
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (indicatorVisible && getItemCount() > 1) {
            drawIndicator(canvas)
        }
    }

    /**
     * 绘制内部 Indicator。
     *
     * 注意：
     *
     * Indicator 使用 currentItem。
     *
     * 例如真实数据：
     *
     * 0 1 2
     *
     * 虚拟页面：
     *
     * 2 0 1 2 0
     *
     * 即使当前滚动到了虚拟的 0 或 4，
     * Indicator 仍然只显示：
     *
     * 0 1 2
     *
     * 不会出现 5 个圆点。
     */
    private fun drawIndicator(canvas: Canvas) {
        val count = getItemCount()
        if (count <= 1) {
            return
        }
        val totalWidth = count * indicatorRadius * 2 + (count - 1) * indicatorSpace
        var startX = (width - totalWidth) / 2f
        val centerY = height - indicatorBottomMargin - indicatorRadius
        for (position in 0 until count) {
            indicatorPaint.color =
                if (position == currentItem) {
                    indicatorSelectedColor
                } else {
                    indicatorNormalColor
                }
            canvas.drawCircle(startX + indicatorRadius, centerY, indicatorRadius,
                indicatorPaint
            )
            startX += indicatorRadius * 2 + indicatorSpace
        }
    }

    // -------------------------------------------------------------------------
    // Page Change Listener
    // -------------------------------------------------------------------------

    /**
     * 添加页面变化监听。
     */
    fun addOnPageChangeListener(listener: OnPageChangeListener) {
        if (!pageChangeListeners.contains(listener)) {
            pageChangeListeners.add(listener)
        }
    }

    /**
     * 移除页面变化监听。
     */
    fun removeOnPageChangeListener(listener: OnPageChangeListener) {
        pageChangeListeners.remove(listener)
    }

    /**
     * 移除全部页面变化监听。
     */
    fun removeAllOnPageChangeListeners() {
        pageChangeListeners.clear()
    }

    /**
     * 页面滚动回调。
     */
    private fun dispatchPageScrolled() {
        if (width <= 0 || childCount <= 0) {
            return
        }
        val scrollXValue = scrollX
        val page = (scrollXValue / width)
                .coerceIn(
                    0,
                    childCount - 1
                )
        val positionOffsetPixels = scrollXValue - page * width
        val positionOffset =
            if (width > 0) {
                positionOffsetPixels
                    .toFloat() /
                        width.toFloat()
            } else {
                0f
            }
        val actualPosition = getActualPosition(page)

        /*
         * 首尾虚拟页面需要特殊处理。
         *
         * 真实：
         * 0 1 2
         *
         * 虚拟：
         * 2 0 1 2 0
         *
         * 对外：
         * 0 1 2
         *
         * 而不是：
         * 2 0 1 2 0
         */
        val listeners = pageChangeListeners.toList()
        listeners.forEach {
            it.onPageScrolled(
                actualPosition,
                positionOffset,
                positionOffsetPixels
            )
        }
    }

    /**
     * 获取真实数据位置。
     */
    private fun getActualPosition(page: Int): Int {
        val count = getItemCount()
        if (count <= 0) {
            return 0
        }
        if (!loopEnabled || count <= 1) {
            return page.coerceIn(
                0,
                count - 1
            )
        }
        return when (page) {
            0 -> {
                count - 1
            }
            childCount - 1 -> {
                0
            }
            else -> {
                page - 1
            }
        }
    }

    /**
     * 页面选中回调。
     */
    private fun dispatchPageSelected() {
        val listeners = pageChangeListeners.toList()
        listeners.forEach {
            it.onPageSelected(currentItem)
        }
    }

    /**
     * 页面滚动状态回调。
     */
    private fun dispatchScrollStateChanged(state: Int) {
        if (scrollState == state) {
            return
        }
        scrollState = state
        val listeners = pageChangeListeners.toList()
        listeners.forEach {
            it.onPageScrollStateChanged(state)
        }
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (getItemCount() <= 1) {
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
                // 注意：
                // 这里不能进入 DRAGGING。
                //
                // 只有真正超过 touchSlop 并确认横向移动后，
                // 才进入 SCROLL_STATE_DRAGGING。
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!dragging) {
                    if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy)) {
                        // 到这里才算真正开始拖动
                        dragging = true
                        moved = true
                        dispatchScrollStateChanged(SCROLL_STATE_DRAGGING)
                    }
                }
                if (dragging) {
                    scrollBy((-dx).toInt(), 0)
                    limitScrollRange()
                    dispatchPageScrolled()
                }
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // 真正拖动过：
                    // DRAGGING -> SETTLING -> IDLE
                    handleRelease()
                } else {
                    // 没有真正开始移动：
                    // 不应该出现 DRAGGING。
                    dispatchScrollStateChanged(
                        SCROLL_STATE_IDLE
                    )
                    if (!moved) {
                        performBannerClick()
                    }
                    startAutoPlay()
                }
                recycleVelocityTracker()
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 已经真正拖动过，回到最近页面
                    settleToNearestPage()
                } else {
                    // 从未真正移动，不经过 DRAGGING
                    dispatchScrollStateChanged(SCROLL_STATE_IDLE)
                    startAutoPlay()
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

    private fun performBannerClick() {
        val count = getItemCount()
        if (count <= 0) {
            return
        }
        val position = currentItem.coerceIn(0, count - 1)
        onBannerClickListener?.onBannerClick(this, position)
    }

    /**
     * 手指释放。
     */
    private fun handleRelease() {
        val pageWidth = width
        if (pageWidth <= 0 || childCount <= 0 || getItemCount() <= 1) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            startAutoPlay()
            return
        }
        velocityTracker?.computeCurrentVelocity(
            1000,
            maximumVelocity.toFloat()
        )
        val velocityX = velocityTracker?.xVelocity ?: 0f
        val currentScroll = scrollX
        val currentPageFloat = currentScroll.toFloat() / pageWidth
        var targetPage = currentPageFloat.toInt()
        val offset = currentPageFloat - targetPage
        if (abs(velocityX) >= minimumVelocity) {
            targetPage = if (velocityX < 0) {
                targetPage + 1
            } else {
                targetPage
            }
        } else if (offset >= 0.5f) {
            targetPage++
        }
        // childCount > 0 后才允许 coerceIn。
        targetPage = targetPage.coerceIn(
            0,
            childCount - 1
        )
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(targetPage)
    }

    /**
     * 回到最近页面。
     */
    private fun settleToNearestPage() {
        if (width <= 0 || childCount <= 0 || getItemCount() <= 1) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            startAutoPlay()
            return
        }
        val targetPage =
            (scrollX.toFloat() / width + 0.5f)
                .toInt()
                .coerceIn(
                    0,
                    childCount - 1
                )
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(targetPage)
    }

    // -------------------------------------------------------------------------
    // Scroll
    // -------------------------------------------------------------------------

    /**
     * 平滑滚动到页面。
     */
    private fun smoothScrollToPage(page: Int) {
        if (width <= 0 || childCount <= 0) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            startAutoPlay()
            return
        }
        val targetPage = page.coerceIn(0, childCount - 1)
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
     * 限制滚动范围。
     */
    private fun limitScrollRange() {
        val maxScroll = max(0, (childCount - 1) * width)
        if (scrollX < 0) {
            scrollTo(0, 0)
        } else if (scrollX > maxScroll) {
            scrollTo(maxScroll, 0)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            dispatchPageScrolled()
            postInvalidateOnAnimation()
            return
        }
        if (childCount <= 0 || width <= 0) {
            return
        }
        val page = (scrollX.toFloat() / width)
            .toInt()
            .coerceIn(
                0,
                childCount - 1
            )
        if (scrollX == page * width) {
            finishScroll(page)
        }
    }

    /**
     * 完成一次页面滚动。
     */
    private fun finishScroll(page: Int) {
        val count = getItemCount()
        if (count <= 0) {
            return
        }
        if (loopEnabled && count > 1) {
            when (page) {
                /*
                 * 滚动到了最前面的虚拟页面。
                 *
                 * 虚拟：
                 * 2 | 0 | 1 | 2 | 0
                 *
                 * 当前页面：
                 * 2
                 *
                 * 瞬间跳到真实页面：
                 * 2
                 */
                0 -> {
                    currentItem = count - 1
                    currentPage = count
                    scrollTo(currentPage * width, 0)
                    dispatchPageSelected()
                }
                /*
                 * 滚动到了最后面的虚拟页面。
                 *
                 * 虚拟：
                 * 2 | 0 | 1 | 2 | 0
                 *
                 * 当前页面：
                 * 0
                 *
                 * 瞬间跳到真实页面：
                 * 0
                 */
                childCount - 1 -> {
                    currentItem = 0
                    currentPage = 1
                    scrollTo(currentPage * width, 0)
                    dispatchPageSelected()
                }
                else -> {
                    val newItem = page - 1
                    if (newItem != currentItem) {
                        currentItem = newItem
                        currentPage = page
                        dispatchPageSelected()
                    }
                }
            }
        } else {
            val newItem = page.coerceIn(0, count - 1)
            if (newItem != currentItem) {
                currentItem = newItem
                currentPage = newItem
                dispatchPageSelected()
            }
        }
        dispatchPageScrolled()
        dispatchScrollStateChanged(SCROLL_STATE_IDLE)
        invalidate()
        startAutoPlay()
    }

    // -------------------------------------------------------------------------
    // Measure / Layout
    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveBannerHeight(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidthSpec,
                childHeightSpec)
        }
    }

    /**
     * 计算 Banner 高度。
     */
    private fun resolveBannerHeight(heightMeasureSpec: Int): Int {
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

    // -------------------------------------------------------------------------
    // Click
    // -------------------------------------------------------------------------

    /**
     * 设置 Banner 点击监听。
     */
    fun setOnBannerClickListener(listener: OnBannerClickListener) {
        onBannerClickListener = listener
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAutoPlay()
    }

    override fun onDetachedFromWindow() {
        stopAutoPlay()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

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
            currentItem = state.getInt(KEY_CURRENT_ITEM, 0)
            val superState = state.getParcelable<Parcelable>(KEY_SUPER_STATE)
            super.onRestoreInstanceState(superState)
            post {
                val count = getItemCount()
                if (count > 0) {
                    currentItem = currentItem.coerceIn(0, count - 1)
                    currentPage =
                        if (loopEnabled && count > 1) {
                            currentItem + 1
                        } else {
                            currentItem
                        }
                    if (width > 0) {
                        scrollTo(currentPage * width, 0)
                    }
                    dispatchPageSelected()
                    dispatchPageScrolled()
                }
            }
            return
        }
        super.onRestoreInstanceState(state)
    }

    // -------------------------------------------------------------------------
    // Velocity Tracker
    // -------------------------------------------------------------------------

    private fun ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // -------------------------------------------------------------------------
    // Utils
    // -------------------------------------------------------------------------

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
     * Banner 点击监听。
     */
    interface OnBannerClickListener {

        fun onBannerClick(view: DoraBannerView, position: Int)
    }

    /**
     * Banner 页面变化监听。
     *
     * API 设计与 ViewPager2 的
     * OnPageChangeCallback 保持一致。
     */
    interface OnPageChangeListener {

        /**
         * 页面滚动。
         *
         * position：
         * 真实数据位置。
         *
         * positionOffset：
         * 当前页面偏移比例，0 ~ 1。
         *
         * positionOffsetPixels：
         * 当前页面偏移像素。
         */
        fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int)

        /**
         * 页面选中。
         */
        fun onPageSelected(position: Int)

        /**
         * 页面滚动状态变化。
         */
        fun onPageScrollStateChanged(state: Int)
    }

    /**
     * Drawable Adapter 内部标记。
     *
     * 用于兼容 addBanner(Drawable)。
     */
    private interface SimpleDrawableAdapter

    companion object {

        /**
         * 当前没有滚动。
         */
        const val SCROLL_STATE_IDLE = 0

        /**
         * 用户正在拖动。
         */
        const val SCROLL_STATE_DRAGGING = 1

        /**
         * 正在自动 / 手动滚动。
         */
        const val SCROLL_STATE_SETTLING = 2

        /**
         * 默认自动播放间隔。
         */
        private const val DEFAULT_INTERVAL = 3000L

        /**
         * 默认滑动动画时间。
         */
        private const val DEFAULT_DURATION = 300L

        /**
         * 最小自动播放间隔。
         */
        private const val MIN_INTERVAL = 500L

        /**
         * Super State。
         */
        private const val KEY_SUPER_STATE = "super_state"

        /**
         * 当前页面。
         */
        private const val KEY_CURRENT_ITEM = "current_item"
    }
}
