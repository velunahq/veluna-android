package com.example.ui.theme

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

/**
 * Motion tokens tuned for high-refresh-rate displays (90Hz / 120Hz / 144Hz+).
 * Physics-based springs naturally interpolate per hardware VSYNC with zero fixed-FPS stepping.
 */
object VelunaMotion {
    fun <T> expressiveSpatialSpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.90f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = visibilityThreshold
        )

    fun expressiveIntOffsetSpring(): FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = 0.92f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = IntOffset(1, 1)
        )

    fun <T> snappySpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
            visibilityThreshold = visibilityThreshold
        )

    fun snappyIntOffsetSpring(): FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
            visibilityThreshold = IntOffset(1, 1)
        )

    fun screenSlideEnterSpring(): FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = 0.92f,
            stiffness = 480f,
            visibilityThreshold = IntOffset(1, 1)
        )

    fun screenSlideExitTween(): FiniteAnimationSpec<IntOffset> =
        tween(durationMillis = 175, easing = FastOutSlowInEasing)

    fun screenFadeInTween(): FiniteAnimationSpec<Float> =
        tween(durationMillis = 185, easing = FastOutSlowInEasing)

    fun screenFadeOutTween(): FiniteAnimationSpec<Float> =
        tween(durationMillis = 140, easing = FastOutLinearInEasing)

    fun tabTransitionTween(): FiniteAnimationSpec<Float> =
        tween(durationMillis = 175, easing = FastOutSlowInEasing)

    fun tabFadeOutTween(): FiniteAnimationSpec<Float> =
        tween(durationMillis = 140, easing = FastOutLinearInEasing)

    fun tabSlideSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = 0.92f,
            stiffness = 540f,
            visibilityThreshold = 0.002f
        )

    fun tabPillSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = 0.86f,
            stiffness = 520f
        )

    fun bouncyScaleSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMedium
        )

    fun smoothFloatSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

    fun fastFade(): FiniteAnimationSpec<Float> = tween(durationMillis = 140, easing = FastOutSlowInEasing)
}

private class SmoothDecayFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        var velocityLeft = initialVelocity
        var lastValue = 0f
        val animationState = AnimationState(
            initialValue = 0f,
            initialVelocity = initialVelocity
        )
        animationState.animateDecay(flingDecay) {
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            velocityLeft = this.velocity
            if (abs(delta - consumed) > 0.5f) {
                cancelAnimation()
            }
        }
        return velocityLeft
    }
}

@Composable
fun rememberVelunaFlingBehavior(): FlingBehavior {
    val decaySpec = remember {
        exponentialDecay<Float>(
            frictionMultiplier = 0.76f,
            absVelocityThreshold = 0.5f
        )
    }
    return remember(decaySpec) { SmoothDecayFlingBehavior(decaySpec) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberVelunaLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    nestedPrefetchItemCount: Int = 4
): LazyListState {
    val prefetchStrategy = remember(nestedPrefetchItemCount) {
        LazyListPrefetchStrategy(nestedPrefetchItemCount = nestedPrefetchItemCount)
    }
    return rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        prefetchStrategy = prefetchStrategy
    )
}

