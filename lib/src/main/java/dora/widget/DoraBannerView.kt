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
import kotlin.math.roundToInt
import androidx.core.view.isNotEmpty
import androidx.core.graphics.withTranslation

/**
 * 横幅轮播控件。
 *
 * DoraBannerView 是一个轻量级 Banner / Carousel 控件。
 *
 * 支持：
 *
 * - BannerAdapter
 * - Drawable 数据
 * - DrawableRes 数据
 * - 自定义 View 数据
 * - 无限循环
 * - 自动播放
 * - 手指左右滑动
 * - 点击事件
 * - 页面滚动监听
 * - 内置 Indicator
 * - 保存 / 恢复当前页面
 *
 * 无限循环采用“首尾各增加一个虚拟页面”的方式实现。
 *
 * 例如真实数据：
 *
 *     0 1 2
 *
 * 实际 View：
 *
 *     2 | 0 | 1 | 2 | 0
 *     ↑               ↑
 *   虚拟             虚拟
 *
 * 对外暴露的 currentItem 始终是真实数据位置。
 */
class DoraBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * Banner Adapter。
     *
     * Adapter 负责：
     *
     * 1. 提供数据数量。
     * 2. 创建 Banner View。
     * 3. 将数据绑定到 Banner View。
     */
    private var adapter: BannerAdapter<*, *>? = null

    /**
     * 当前真实数据位置。
     *
     * 这个位置不包含无限循环产生的虚拟页面。
     *
     * 例如：
     *
     *     虚拟页面：2 | 0 | 1 | 2 | 0
     *     真实位置：    0   1   2
     *
     * currentItem 只可能是 0、1、2。
     */
    private var currentItem = 0

    /**
     * 当前实际 View 页面。
     *
     * 如果开启循环：
     *
     *     真实：0 1 2
     *     实际：2 0 1 2 0
     *
     * 那么：
     *
     *     currentItem = 0
     *     currentPage = 1
     *
     *     currentItem = 2
     *     currentPage = 3
     */
    private var currentPage = 0

    /**
     * 是否自动播放。
     */
    private var isAutoPlayEnabled = true

    /**
     * 自动播放间隔，单位：毫秒。
     */
    private var autoPlayInterval = DEFAULT_INTERVAL

    /**
     * 页面滚动动画持续时间，单位：毫秒。
     */
    private var scrollDuration = DEFAULT_DURATION

    /**
     * 是否开启无限循环。
     */
    private var loopEnabled = true

    /**
     * 当前滚动状态。
     *
     * 状态与 ViewPager2 的设计保持一致：
     *
     * IDLE：
     *     当前没有滚动。
     *
     * DRAGGING：
     *     用户正在手指拖动。
     *
     * SETTLING：
     *     正在执行自动 / 手动页面动画。
     */
    private var scrollState = SCROLL_STATE_IDLE

    /**
     * 页面变化监听器集合。
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
     * Indicator 圆点之间的间距。
     */
    private var indicatorSpace = dp2px(8f).toFloat()

    /**
     * Indicator 距离底部的距离。
     */
    private var indicatorBottomMargin = dp2px(12f)

    /**
     * 未选中的 Indicator 颜色。
     */
    private var indicatorNormalColor = 0x66FFFFFF

    /**
     * 当前选中的 Indicator 颜色。
     */
    private var indicatorSelectedColor = 0xFFFFFFFF.toInt()

    /**
     * Indicator 绘制画笔。
     */
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 页面滚动器。
     *
     * Scroller 只负责计算动画过程中的 scrollX，
     * 实际滚动由 computeScroll() 完成。
     */
    private val scroller = Scroller(context, DecelerateInterpolator())

    /**
     * 系统判定为一次有效拖动所需要的最小距离。
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 手指最大滑动速度。
     */
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /**
     * 手指触发快速翻页所需要的最小速度。
     */
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /**
     * 用于计算手指抬起时的横向滑动速度。
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
     * 上一次 MotionEvent 的 X 坐标。
     */
    private var lastX = 0f

    /**
     * 当前是否已经进入真正的拖动状态。
     *
     * 注意：
     *
     * ACTION_DOWN 时不能直接进入 DRAGGING。
     *
     * 必须超过 touchSlop，
     * 并且横向移动距离大于纵向移动距离，
     * 才认为用户正在左右拖动 Banner。
     */
    private var dragging = false

    /**
     * 本次触摸过程中是否发生过有效移动。
     */
    private var moved = false

    /**
     * 当前是否已经安排了自动播放任务。
     *
     * 防止 onLayout() 等生命周期方法反复重置倒计时。
     */
    private var autoPlayScheduled = false

    /**
     * 自动播放任务。
     *
     * 自动播放流程：
     *
     *     等待 interval
     *         ↓
     *     currentPage + 1
     *         ↓
     *     Scroller 动画
     *         ↓
     *     computeScroll()
     *         ↓
     *     finishScroll()
     *         ↓
     *     再次等待 interval
     */
    private val autoPlayRunnable = Runnable {
        autoPlayScheduled = false
        if (!isAttachedToWindow) {
            return@Runnable
        }
        if (!isAutoPlayEnabled) {
            return@Runnable
        }
        val count = getItemCount()
        if (count <= 1) {
            return@Runnable
        }
        if (width <= 0 || height <= 0 || childCount <= 0) {
            return@Runnable
        }
        if (!scroller.isFinished) {
            return@Runnable
        }
        if (!loopEnabled && currentPage >= childCount - 1) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            return@Runnable
        }
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(currentPage + 1)
    }

    init {
        /*
         * ViewGroup 默认可能不会调用 onDraw()。
         *
         * 虽然当前 Indicator 使用 dispatchDraw() 绘制，
         * 这里仍然关闭 ViewGroup 的 willNotDraw，
         * 方便后续扩展背景 / 装饰绘制。
         */
        setWillNotDraw(false)
        /*
         * 读取 XML 属性。
         */
        context.withStyledAttributes(
            attrs,
            R.styleable.DoraBannerView,
            defStyleAttr,
            0
        ) {
            /**
             * 是否自动播放。
             */
            isAutoPlayEnabled =
                getBoolean(
                    R.styleable.DoraBannerView_dview_bv_autoPlay,
                    isAutoPlayEnabled
                )
            /**
             * 自动播放间隔。
             */
            autoPlayInterval =
                getInt(
                    R.styleable.DoraBannerView_dview_bv_interval,
                    DEFAULT_INTERVAL.toInt()
                )
                    .coerceAtLeast(
                        MIN_INTERVAL.toInt()
                    )
                    .toLong()
            /**
             * 页面动画持续时间。
             */
            scrollDuration =
                getInt(
                    R.styleable.DoraBannerView_dview_bv_duration,
                    DEFAULT_DURATION.toInt()
                )
                    .coerceAtLeast(0)
                    .toLong()
            /**
             * 是否无限循环。
             */
            loopEnabled =
                getBoolean(
                    R.styleable.DoraBannerView_dview_bv_loop,
                    true
                )
            /**
             * 是否显示 Indicator。
             */
            indicatorVisible =
                getBoolean(
                    R.styleable.DoraBannerView_dview_bv_indicatorVisible,
                    indicatorVisible
                )
            /**
             * Indicator 半径。
             */
            indicatorRadius =
                getDimension(
                    R.styleable.DoraBannerView_dview_bv_indicatorRadius,
                    indicatorRadius
                )
            /**
             * Indicator 间距。
             */
            indicatorSpace =
                getDimension(
                    R.styleable.DoraBannerView_dview_bv_indicatorSpace,
                    indicatorSpace
                )
            /**
             * Indicator 底部间距。
             */
            indicatorBottomMargin =
                getDimensionPixelSize(
                    R.styleable.DoraBannerView_dview_bv_indicatorBottomMargin,
                    indicatorBottomMargin
                )
            /**
             * Indicator 未选中颜色。
             */
            indicatorNormalColor =
                getColor(
                    R.styleable.DoraBannerView_dview_bv_indicatorNormalColor,
                    indicatorNormalColor
                )
            /**
             * Indicator 选中颜色。
             */
            indicatorSelectedColor =
                getColor(
                    R.styleable.DoraBannerView_dview_bv_indicatorSelectedColor,
                    indicatorSelectedColor
                )
        }
    }

    // =========================================================================
    // Adapter
    // =========================================================================

    /**
     * 设置 Banner Adapter。
     *
     * Adapter 数据发生变化后，
     * DoraBannerView 会重新创建所有 Banner View。
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
        currentItem =
            currentItem.coerceIn(
                0,
                max(
                    0,
                    getItemCount() - 1
                )
            )
        rebuildAdapterViews()
    }

    /**
     * 获取当前 Adapter。
     */
    fun getAdapter(): BannerAdapter<*, *>? {
        return adapter
    }

    /**
     * 根据 Adapter 重新创建 Banner View。
     *
     * 无限循环开启时：
     *
     *     count = 3
     *
     * 最终创建：
     *
     *     2 | 0 | 1 | 2 | 0
     */
    private fun rebuildAdapterViews() {
        stopAutoPlay()
        /*
         * 清除旧页面。
         */
        removeAllViews()
        val count = getItemCount()
        /*
         * 没有数据。
         */
        if (count <= 0) {
            currentItem = 0
            currentPage = 0
            scrollTo(0, 0)
            invalidate()
            return
        }
        /*
         * 修正当前真实页面。
         */
        currentItem = currentItem.coerceIn(0, count - 1)
        /*
         * 无限循环时，
         * 首先添加最后一个虚拟页面。
         */
        if (loopEnabled && count > 1) {
            addAdapterView(count - 1)
        }
        /*
         * 添加所有真实页面。
         */
        for (position in 0 until count) {
            addAdapterView(position)
        }
        /*
         * 无限循环时，
         * 最后添加第一个虚拟页面。
         */
        if (loopEnabled && count > 1) {
            addAdapterView(0)
            /*
             * 真实页面 0 对应实际页面 1。
             */
            currentPage = currentItem + 1
        } else {
            currentPage = currentItem
        }
        /*
         * 子 View 数量发生变化，需要重新布局。
         */
        requestLayout()
        /*
         * 等待 View 完成测量和布局后，
         * 再同步实际 scrollX。
         */
        post {
            syncCurrentPage()
            dispatchPageSelected()
            dispatchPageScrolled()
        }
        invalidate()
    }

    /**
     * 将当前页面同步到实际 scrollX。
     *
     * 只有 View 已经完成测量，
     * width > 0 时才能计算页面位置。
     */
    private fun syncCurrentPage() {
        if (width <= 0 || childCount <= 0) {
            return
        }
        currentPage = currentPage.coerceIn(0, childCount - 1)
        scrollTo(currentPage * width, 0)
    }

    /**
     * 创建并绑定一个 Adapter View。
     *
     * @param position 真实数据位置。
     */
    private fun addAdapterView(position: Int) {
        val currentAdapter = adapter ?: return
        /*
         * 创建 View。
         */
        val child = currentAdapter.onCreateView(context)
        /*
         * 获取对应数据。
         */
        val item = currentAdapter.getItem(position) ?: return
        /*
         * Adapter 的泛型由外部决定，
         * 这里进行一次受控类型转换。
         */
        @Suppress("UNCHECKED_CAST")
        (currentAdapter as BannerAdapter<Any, View>)
            .onBindView(child, item, position)
        /*
         * 添加到 ViewGroup。
         */
        addView(child)
    }

    /**
     * 安排下一次自动播放。
     *
     * 注意：
     *
     * 这里只负责“等待”，
     * 不负责执行页面滚动。
     *
     * 真正执行滚动由 autoPlayRunnable 完成。
     */
    /**
     * 安排下一次自动播放。
     *
     * 注意：
     *
     * 这里只负责注册一次自动播放任务。
     * 不在这里递归等待 View 完成布局。
     *
     * View 的布局状态由 onLayout() 负责处理。
     */
    /**
     * 安排下一次自动播放。
     *
     * 自动播放始终只有一个 Runnable。
     */
    private fun scheduleAutoPlay() {
        /*
         * 未开启自动播放。
         */
        if (!isAutoPlayEnabled) {
            return
        }
        /*
         * View 尚未 attach。
         */
        if (!isAttachedToWindow) {
            return
        }
        /*
         * 数据不足两个页面。
         */
        if (getItemCount() <= 1) {
            return
        }
        /*
         * View 尚未完成布局。
         */
        if (width <= 0 || height <= 0 || childCount <= 0) {
            return
        }
        /*
         * 当前正在滚动。
         *
         * 等 finishScroll() 后再重新安排。
         */
        if (!scroller.isFinished) {
            return
        }
        /*
         * 已经有一个自动播放任务，
         * 不要再次 postDelayed。
         */
        if (autoPlayScheduled) {
            return
        }
        autoPlayScheduled = true
        postDelayed(
            autoPlayRunnable,
            autoPlayInterval
        )
    }

    // =========================================================================
    // Drawable / View API
    // =========================================================================

    /**
     * 设置 Drawable Banner。
     */
    fun setBanners(vararg drawables: Drawable) {
        setBanners(drawables.toList())
    }

    /**
     * 设置 Drawable Banner。
     */
    fun setBanners(drawables: List<Drawable>) {
        setAdapter(object : BannerAdapter<Drawable, View>() {

            override fun getItemCount(): Int {
                return drawables.size
            }

            override fun onCreateView(context: Context): View {
                return ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            }

            override fun onBindView(
                view: View,
                model: Drawable,
                position: Int
            ) {
                (view as ImageView).setImageDrawable(model)
            }

            override fun getItem(position: Int): Drawable? {
                return drawables.getOrNull(position)
            }
        })
    }

    /**
     * 设置 DrawableRes Banner。
     */
    fun setBanners(@DrawableRes vararg drawableResIds: Int) {
        val drawables = ArrayList<Drawable>()
        drawableResIds.forEach { resId ->
            ContextCompat.getDrawable(context, resId)
                ?.let {
                    drawables.add(it)
                }
        }
        setBanners(drawables)
    }

    /**
     * 添加 Drawable Banner。
     *
     * 注意：
     *
     * 由于当前 Adapter 可能不是 Drawable Adapter，
     * 因此这里只能读取能够转换为 Drawable 的数据。
     */
    fun addBanner(drawable: Drawable) {
        val list = ArrayList<Drawable>()
        val currentAdapter = adapter
        if (currentAdapter != null) {
            for (i in 0 until getItemCount()) {
                val item = currentAdapter.getItem(i)
                if (item is Drawable) {
                    list.add(item)
                }
            }
        }
        list.add(drawable)
        setBanners(list)
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
     * 清空所有 Banner。
     */
    fun clearBanners() {
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

    // =========================================================================
    // Basic API
    // =========================================================================

    /**
     * 获取真实 Banner 数量。
     */
    fun getItemCount(): Int {
        return adapter?.getItemCount() ?: 0
    }

    /**
     * 获取当前真实页面位置。
     */
    fun getCurrentItem(): Int {
        return currentItem
    }

    /**
     * 设置当前页面。
     *
     * @param index 真实数据位置。
     * @param smoothScroll 是否执行滚动动画。
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
            if (
                loopEnabled &&
                count > 1
            ) {
                target + 1
            } else {
                target
            }
        /*
         * 用户主动设置页面后，
         * 重新计算自动播放计时。
         */
        stopAutoPlay()
        if (smoothScroll) {
            dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
            smoothScrollToPage(currentPage)
        } else {
            syncCurrentPage()
            dispatchPageSelected()
            dispatchPageScrolled()
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            invalidate()
            scheduleAutoPlay()
        }
    }

    // =========================================================================
    // Auto Play
    // =========================================================================

    /**
     * 开启或关闭自动播放。
     */
    fun setAutoPlay(enabled: Boolean) {
        if (isAutoPlayEnabled == enabled) {
            if (enabled) {
                scheduleAutoPlay()
            }
            return
        }
        isAutoPlayEnabled = enabled
        if (enabled) {
            scheduleAutoPlay()
        } else {
            stopAutoPlay()
        }
    }

    /**
     * 设置自动播放间隔。
     *
     * 最小值为 MIN_INTERVAL。
     */
    fun setAutoPlayInterval(interval: Long) {
        autoPlayInterval = interval.coerceAtLeast(MIN_INTERVAL)
        if (isAutoPlayEnabled) {
            stopAutoPlay()
            scheduleAutoPlay()
        }
    }

    /**
     * 设置页面滚动动画持续时间。
     */
    fun setScrollDuration(duration: Long) {
        scrollDuration = duration.coerceAtLeast(0)
    }

    /**
     * 开始自动播放。
     *
     * 如果当前 View 尚未 attach，
     * 或者还没有完成布局，
     * 会自动等待合适的生命周期。
     */
    fun startAutoPlay() {
        scheduleAutoPlay()
    }

    /**
     * 停止自动播放。
     */
    fun stopAutoPlay() {
        removeCallbacks(autoPlayRunnable)
        autoPlayScheduled = false
    }

    // =========================================================================
    // Loop
    // =========================================================================

    /**
     * 设置是否开启无限循环。
     */
    fun setLoopEnabled(enabled: Boolean) {
        if (loopEnabled == enabled) {
            return
        }
        loopEnabled = enabled
        rebuildAdapterViews()
    }

    // =========================================================================
    // Indicator
    // =========================================================================

    /**
     * 设置内部 Indicator 是否显示。
     */
    fun setIndicatorVisible(visible: Boolean) {
        indicatorVisible = visible
        /*
         * Indicator 属于 ViewGroup 自己绘制的内容，
         * 修改后需要立即重新绘制。
         */
        invalidate()
    }

    /**
     * 获取内部 Indicator 是否显示。
     */
    fun isIndicatorVisible(): Boolean {
        return indicatorVisible
    }

    /**
     * 绘制 Banner 子 View 和 Indicator。
     *
     * Indicator 放在 dispatchDraw() 最后绘制，
     * 因此始终显示在 Banner 内容上方。
     */
    override fun dispatchDraw(canvas: Canvas) {
        /*
         * 先绘制 Banner 子 View。
         */
        super.dispatchDraw(canvas)
        /*
         * Indicator 属于 DoraBannerView 自己的固定内容，
         * 不应该跟随 Banner 的 scrollX 一起移动。
         *
         * ViewGroup 当前 Canvas 会受到 scrollX 的影响，
         * 因此这里需要抵消 Banner 的水平滚动。
         */
        if (indicatorVisible && getItemCount() > 1) {
            canvas.withTranslation(scrollX.toFloat(), 0f) {
                /*
                 * 抵消 View 当前的 scrollX。
                 *
                 * 例如：
                 *
                 *     scrollX = width
                 *
                 * Canvas 中的内容相当于向左移动了 width，
                 * Indicator 需要向右补偿 width，
                 * 才能始终固定在 DoraBannerView 的底部。
                 */
                drawIndicator(this)
            }
        }
    }

    /**
     * 绘制内部 Indicator。
     *
     * 例如：
     *
     *     真实数据：
     *     0 1 2
     *
     *     虚拟页面：
     *     2 | 0 | 1 | 2 | 0
     *
     * Indicator 永远只有：
     *
     *     ● ○ ○
     *
     * 而不会因为虚拟页面变成：
     *
     *     ○ ○ ○ ○ ○
     */
    private fun drawIndicator(canvas: Canvas) {
        val count = getItemCount()
        if (!indicatorVisible || count <= 1) {
            return
        }
        if (width <= 0 || height <= 0) {
            return
        }
        val radius = indicatorRadius.coerceAtLeast(0f)
        val space = indicatorSpace.coerceAtLeast(0f)
        /*
         * 所有 Indicator 的总宽度。
         */
        val totalWidth = count * radius * 2f + (count - 1) * space
        /*
         * 水平居中。
         */
        val startX = (width - totalWidth) / 2f
        /*
         * Indicator 圆心 Y。
         */
        val centerY = height - indicatorBottomMargin - radius
        /*
         * 如果 Indicator 已经超出顶部，
         * 则不绘制。
         */
        if (centerY < radius) {
            return
        }
        var x = startX
        for (position in 0 until count) {
            indicatorPaint.color =
                if (position == currentItem) {
                    indicatorSelectedColor
                } else {
                    indicatorNormalColor
                }
            canvas.drawCircle(
                x + radius,
                centerY,
                radius,
                indicatorPaint
            )
            x += radius * 2f + space
        }
    }

    // =========================================================================
    // Page Change Listener
    // =========================================================================

    /**
     * 添加页面变化监听器。
     */
    fun addOnPageChangeListener(listener: OnPageChangeListener) {
        if (!pageChangeListeners.contains(listener)) {
            pageChangeListeners.add(listener)
        }
    }

    /**
     * 移除页面变化监听器。
     */
    fun removeOnPageChangeListener(listener: OnPageChangeListener) {
        pageChangeListeners.remove(listener)
    }

    /**
     * 移除所有页面变化监听器。
     */
    fun removeAllOnPageChangeListeners() {
        pageChangeListeners.clear()
    }

    /**
     * 分发页面滚动事件。
     */
    private fun dispatchPageScrolled() {
        if (width <= 0 || childCount <= 0) {
            return
        }
        val scrollXValue = scrollX
        val page = (scrollXValue / width).coerceIn(0, childCount - 1)
        val positionOffsetPixels = scrollXValue - page * width
        val positionOffset = positionOffsetPixels.toFloat() / width.toFloat()
        val actualPosition = getActualPosition(page)
        /*
         * 使用副本进行回调，
         * 防止监听器在回调过程中修改集合。
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
     * 将实际 View 页面转换成真实数据位置。
     *
     * 无限循环：
     *
     *     实际页面：0 1 2 3 4
     *     数据位置：2 0 1 2 0
     */
    private fun getActualPosition(page: Int): Int {
        val count = getItemCount()
        if (count <= 0) {
            return 0
        }
        if (!loopEnabled || count <= 1) {
            return page.coerceIn(0, count - 1)
        }
        return when (page) {
            /*
             * 第一个虚拟页面。
             */
            0 -> {
                count - 1
            }
            /*
             * 最后一个虚拟页面。
             */
            childCount - 1 -> {
                0
            }
            /*
             * 中间是真实页面。
             */
            else -> {
                page - 1
            }
        }
    }

    /**
     * 分发页面选中事件。
     */
    private fun dispatchPageSelected() {
        val listeners = pageChangeListeners.toList()
        listeners.forEach {
            it.onPageSelected(currentItem)
        }
    }

    /**
     * 分发滚动状态变化。
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

    // =========================================================================
    // Touch
    // =========================================================================

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                moved = false
                stopAutoPlay()
                scroller.abortAnimation()
                ensureVelocityTracker()
                velocityTracker?.addMovement(event)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging &&
                    abs(dx) > touchSlop &&
                    abs(dx) > abs(dy)
                ) {
                    dragging = true
                    moved = true
                    dispatchScrollStateChanged(SCROLL_STATE_DRAGGING)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return dragging
    }

    /**
     * 处理 Banner 手势。
     *
     * 手势流程：
     *
     *     DOWN
     *       ↓
     *     判断是否超过 touchSlop
     *       ↓
     *     DRAGGING
     *       ↓
     *     UP
     *       ↓
     *     SETTLING
     *       ↓
     *     IDLE
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (getItemCount() <= 1) {
            return super.onTouchEvent(event)
        }
        ensureVelocityTracker()
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                /*
                 * 用户开始操作时，
                 * 暂停自动播放。
                 */
                stopAutoPlay()
                /*
                 * 如果之前还有动画，
                 * 立即停止。
                 */
                scroller.abortAnimation()
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                /*
                 * 尚未开始拖动。
                 */
                if (!dragging) {
                    /*
                     * 必须满足：
                     *
                     * 1. 横向距离超过 touchSlop。
                     * 2. 横向距离大于纵向距离。
                     */
                    if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy)) {
                        dragging = true
                        moved = true
                        dispatchScrollStateChanged(SCROLL_STATE_DRAGGING)
                    }
                }
                /*
                 * 真正拖动 Banner。
                 */
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
                    /*
                     * 用户真正拖动过，
                     * 根据速度和偏移量决定最终页面。
                     */
                    handleRelease()
                } else {
                    /*
                     * 没有真正拖动，
                     * 不应该产生 DRAGGING。
                     */
                    dispatchScrollStateChanged(SCROLL_STATE_IDLE)
                    /*
                     * 没有移动则认为是点击。
                     */
                    if (!moved) {
                        performBannerClick()
                    }
                    scheduleAutoPlay()
                }
                recycleVelocityTracker()
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    /*
                     * 事件被父容器取消，
                     * 回到最近的页面。
                     */
                    settleToNearestPage()
                } else {
                    dispatchScrollStateChanged(SCROLL_STATE_IDLE)
                    scheduleAutoPlay()
                }
                recycleVelocityTracker()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    /**
     * 支持无障碍 / 点击事件。
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 执行 Banner 点击回调。
     */
    private fun performBannerClick() {
        val count = getItemCount()
        if (count <= 0) {
            return
        }
        val position = currentItem.coerceIn(0, count - 1)
        onBannerClickListener?.onBannerClick(this, position)
    }

    /**
     * 手指释放后确定最终页面。
     *
     * 判断规则：
     *
     * 1. 快速滑动：
     *    根据速度决定翻页方向。
     *
     * 2. 普通拖动：
     *    根据当前页面偏移是否超过 50% 决定。
     */
    private fun handleRelease() {
        val pageWidth = width
        if (pageWidth <= 0 || childCount <= 0 || getItemCount() <= 1) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            scheduleAutoPlay()
            return
        }
        velocityTracker?.computeCurrentVelocity(1000, maximumVelocity.toFloat())
        val velocityX = velocityTracker?.xVelocity ?: 0f
        val currentScroll = scrollX
        val currentPageFloat = currentScroll.toFloat() / pageWidth
        var targetPage = currentPageFloat.toInt()
        val offset = currentPageFloat - targetPage
        /*
         * 快速滑动优先使用速度判断。
         */
        if (abs(velocityX) >= minimumVelocity) {
            targetPage =
                if (velocityX < 0) {
                    targetPage + 1
                } else {
                    targetPage
                }
        } else if (offset >= 0.5f) {
            /*
             * 没有明显速度，
             * 根据拖动距离判断。
             */
            targetPage++
        }
        targetPage =
            targetPage.coerceIn(
                0,
                childCount - 1
            )
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(targetPage)
    }

    /**
     * 回到距离当前 scrollX 最近的页面。
     */
    private fun settleToNearestPage() {
        if (width <= 0 || childCount <= 0 || getItemCount() <= 1) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            scheduleAutoPlay()
            return
        }
        val targetPage = (scrollX.toFloat() / width + 0.5f)
            .toInt().coerceIn(0, childCount - 1)
        dispatchScrollStateChanged(SCROLL_STATE_SETTLING)
        smoothScrollToPage(targetPage)
    }

    // =========================================================================
    // Scroll
    // =========================================================================

    /**
     * 平滑滚动到指定实际页面。
     *
     * @param page 实际 View 页面。
     */
    /**
     * 平滑滚动到指定实际页面。
     */
    private fun smoothScrollToPage(page: Int) {
        if (width <= 0 || childCount <= 0) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            return
        }
        val targetPage =
            page.coerceIn(
                0,
                childCount - 1
            )
        val targetX = targetPage * width
        val currentX = scrollX
        val dx = targetX - currentX
        /*
         * 已经在目标页面。
         */
        if (dx == 0) {
            finishScroll(targetPage)
            return
        }
        /*
         * 停止之前可能存在的动画。
         */
        scroller.abortAnimation()
        /*
         * 不需要动画。
         */
        if (scrollDuration <= 0L) {
            scrollTo(targetX, 0)
            finishScroll(targetPage)
            return
        }
        /*
         * 开始动画。
         */
        scroller.startScroll(
            currentX,
            0,
            dx,
            0,
            scrollDuration.toInt()
        )
        /*
         * 请求下一帧。
         */
        postInvalidateOnAnimation()
    }

    /**
     * 限制手指拖动时的 scrollX 范围。
     */
    private fun limitScrollRange() {
        val maxScroll = max(0, (childCount - 1) * width)
        when {
            scrollX < 0 -> {
                scrollTo(0, 0)
            }

            scrollX > maxScroll -> {
                scrollTo(maxScroll, 0)
            }
        }
    }

    /**
     * Scroller 动画回调。
     *
     * 每一帧：
     *
     *     computeScrollOffset()
     *          ↓
     *     获取 currX
     *          ↓
     *     scrollTo()
     *          ↓
     *     dispatchPageScrolled()
     *
     * 动画结束后：
     *
     *     finishScroll()
     */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currX = scroller.currX
            if (scrollX != currX) {
                scrollTo(currX, 0)
            }
            dispatchPageScrolled()
            /*
             * 继续执行 Scroller 动画。
             */
            postInvalidateOnAnimation()
            return
        }
        /*
         * Scroller 已经结束。
         */
        if (childCount <= 0 || width <= 0) {
            return
        }
        /*
         * 根据最终 scrollX 计算页面。
         */
        val page =
            (scrollX.toFloat() / width.toFloat())
                .roundToInt()
                .coerceIn(
                    0,
                    childCount - 1
                )
        /*
         * 强制对齐到页面边界。
         */
        val targetX = page * width
        if (scrollX != targetX) {
            scrollTo(targetX, 0)
        }
        /*
         * 只有真正存在动画结束时，
         * finishScroll 才负责进入 IDLE + 下一轮自动播放。
         */
        finishScroll(page)
    }

    /**
     * 完成一次页面滚动。
     *
     * 这里是无限循环 Banner 最核心的地方。
     *
     * 虚拟页面：
     *
     *     2 | 0 | 1 | 2 | 0
     *
     * 当滚动到最左边虚拟 2：
     *
     *     立即跳到真实 2。
     *
     * 当滚动到最右边虚拟 0：
     *
     *     立即跳到真实 0。
     */
    private fun finishScroll(page: Int) {
        val count = getItemCount()
        if (count <= 0) {
            dispatchScrollStateChanged(SCROLL_STATE_IDLE)
            return
        }
        if (loopEnabled && count > 1) {
            when (page) {
                /*
                 * 左侧虚拟页面。
                 *
                 * 2 | 0 | 1 | 2 | 0
                 * ↑
                 */
                0 -> {
                    currentItem = count - 1
                    currentPage = count
                    scrollTo(currentPage * width, 0)
                    dispatchPageSelected()
                }
                /*
                 * 右侧虚拟页面。
                 *
                 * 2 | 0 | 1 | 2 | 0
                 *                         ↑
                 */
                childCount - 1 -> {
                    currentItem = 0
                    currentPage = 1
                    scrollTo(currentPage * width, 0)
                    dispatchPageSelected()
                }
                /*
                 * 普通真实页面。
                 */
                else -> {
                    val newItem = page - 1
                    currentPage = page
                    if (newItem != currentItem) {
                        currentItem = newItem
                        dispatchPageSelected()
                    }
                }
            }
        } else {
            /*
             * 非循环模式。
             */
            val newItem = page.coerceIn(0, count - 1)
            currentPage = newItem
            if (newItem != currentItem) {
                currentItem = newItem
                dispatchPageSelected()
            }
        }
        /*
         * 更新滚动进度。
         */
        dispatchPageScrolled()
        /*
         * 页面动画结束。
         */
        dispatchScrollStateChanged(SCROLL_STATE_IDLE)
        invalidate()
        /*
         * 非常重要：
         *
         * 只有动画真正结束之后，
         * 才开始下一次 interval 计时。
         */
        scheduleAutoPlay()
    }

    // =========================================================================
    // Measure / Layout
    // =========================================================================

    /**
     * 测量 Banner 本身以及所有子 View。
     *
     * 所有 Banner 子 View 都使用与 DoraBannerView
     * 完全相同的宽高。
     */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveBannerHeight(heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthSpec =
            MeasureSpec.makeMeasureSpec(
                measuredWidth,
                MeasureSpec.EXACTLY
            )
        val childHeightSpec =
            MeasureSpec.makeMeasureSpec(
                measuredHeight,
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
    private fun resolveBannerHeight(heightMeasureSpec: Int): Int {
        return when (MeasureSpec.getMode(heightMeasureSpec)) {
            /*
             * match_parent / 固定 dp 等 EXACTLY 情况。
             */
            MeasureSpec.EXACTLY -> {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            /*
             * 父容器允许的最大高度。
             */
            MeasureSpec.AT_MOST -> {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            /*
             * 没有明确高度时，
             * 使用最小高度。
             */
            else -> {
                suggestedMinimumHeight
            }
        }
    }

    /**
     * 布局所有 Banner 页面。
     *
     * 页面横向排列：
     *
     *     [Page 0][Page 1][Page 2][Page 3]...
     */
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
            syncCurrentPage()
        }
        scheduleAutoPlay()
    }

    // =========================================================================
    // Click
    // =========================================================================

    /**
     * 设置 Banner 点击监听器。
     */
    fun setOnBannerClickListener(listener: OnBannerClickListener?) {
        onBannerClickListener = listener
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * View 加入 Window。
     *
     * 只有真正 attach 后才启动自动播放。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            if (width > 0 && isNotEmpty()) {
                syncCurrentPage()
            }
            scheduleAutoPlay()
        }
    }

    /**
     * View 从 Window 移除。
     *
     * 停止自动播放并释放速度追踪器。
     */
    override fun onDetachedFromWindow() {
        stopAutoPlay()
        recycleVelocityTracker()
        /*
         * 防止 View 离开 Window 后仍然存在 Scroller 动画。
         */
        scroller.abortAnimation()
        super.onDetachedFromWindow()
    }

    // =========================================================================
    // State
    // =========================================================================

    /**
     * 保存当前 Banner 页面。
     */
    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(KEY_SUPER_STATE, super.onSaveInstanceState())
        bundle.putInt(KEY_CURRENT_ITEM, currentItem)
        return bundle
    }

    /**
     * 恢复 Banner 页面。
     */
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            currentItem = state.getInt(KEY_CURRENT_ITEM, 0)
            val superState =
                state.getParcelable<Parcelable>(
                    KEY_SUPER_STATE
                )
            super.onRestoreInstanceState(superState)
            /*
             * 等待 Adapter、测量和布局完成。
             */
            post {
                val count = getItemCount()
                if (count > 0) {
                    currentItem =
                        currentItem.coerceIn(
                            0,
                            count - 1
                        )
                    currentPage =
                        if (loopEnabled && count > 1) {
                            currentItem + 1
                        } else {
                            currentItem
                        }
                    syncCurrentPage()
                    dispatchPageSelected()
                    dispatchPageScrolled()
                    /*
                     * 恢复状态后重新启动自动播放。
                     */
                    scheduleAutoPlay()
                }
            }
            return
        }
        super.onRestoreInstanceState(state)
    }

    // =========================================================================
    // Velocity Tracker
    // =========================================================================

    /**
     * 创建 VelocityTracker。
     */
    private fun ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
    }

    /**
     * 回收 VelocityTracker。
     */
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // =========================================================================
    // Utils
    // =========================================================================

    /**
     * dp 转 px。
     */
    private fun dp2px(dpVal: Float): Int {
        return TypedValue
            .applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dpVal,
                context.resources
                    .displayMetrics
            )
            .toInt()
    }

    /**
     * Banner 点击监听器。
     */
    interface OnBannerClickListener {

        /**
         * Banner 被点击。
         *
         * @param view 当前 DoraBannerView。
         * @param position 真实数据位置。
         */
        fun onBannerClick(view: DoraBannerView, position: Int)
    }

    /**
     * Banner 页面变化监听器。
     *
     * API 设计参考 ViewPager2.OnPageChangeCallback。
     */
    interface OnPageChangeListener {

        /**
         * 页面正在滚动。
         *
         * @param position 当前真实数据位置。
         * @param positionOffset 当前页面偏移比例，范围约为 0 ~ 1。
         * @param positionOffsetPixels 当前页面偏移像素。
         */
        fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        )

        /**
         * 页面选中。
         *
         * @param position 当前真实数据位置。
         */
        fun onPageSelected(position: Int)

        /**
         * 页面滚动状态发生变化。
         *
         * @param state：
         *
         * SCROLL_STATE_IDLE：
         *     当前没有滚动。
         *
         * SCROLL_STATE_DRAGGING：
         *     用户正在拖动。
         *
         * SCROLL_STATE_SETTLING：
         *     正在执行页面动画。
         */
        fun onPageScrollStateChanged(state: Int)
    }

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
         * 正在执行自动 / 手动页面滚动。
         */
        const val SCROLL_STATE_SETTLING = 2

        /**
         * 默认自动播放间隔。
         *
         * 单位：毫秒。
         */
        private const val DEFAULT_INTERVAL = 3000L

        /**
         * 默认页面滚动动画时间。
         *
         * 单位：毫秒。
         */
        private const val DEFAULT_DURATION = 300L

        /**
         * 自动播放允许的最小间隔。
         *
         * 单位：毫秒。
         */
        private const val MIN_INTERVAL = 500L

        /**
         * View 状态保存时的 SuperState Key。
         */
        private const val KEY_SUPER_STATE = "super_state"

        /**
         * View 状态保存时的当前页面 Key。
         */
        private const val KEY_CURRENT_ITEM = "current_item"
    }
}
