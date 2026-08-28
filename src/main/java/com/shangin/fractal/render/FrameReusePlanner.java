package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;
import java.util.Optional;

public final class FrameReusePlanner {

    private static final double INTEGER_SHIFT_EPSILON = 1e-6;

    /**
     * Старый простой API.
     *
     * Оставляем его, чтобы существующие тесты и код,
     * которым нужен только RenderFrame, не пришлось менять.
     */
    public RenderFrame createFrame(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        return plan(
                sourceFrame,
                targetRequest
        ).frame();
    }

    /**
     * Расширенный API.
     *
     * Возвращает не только новый RenderFrame,
     * но и информацию о реально выполненном reuse.
     */
    public FrameReuseResult plan(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        Objects.requireNonNull(
                targetRequest,
                "Target request must not be null"
        );

        if (sourceFrame == null
                || !canReuse(
                sourceFrame,
                targetRequest
        )) {

            return FrameReuseResult.fresh(
                    RenderFrame.create(
                            targetRequest
                    )
            );
        }

        Optional<PixelShift> shift =
                calculatePixelShift(
                        sourceFrame,
                        targetRequest
                );

        if (shift.isEmpty()) {
            return FrameReuseResult.fresh(
                    RenderFrame.create(
                            targetRequest
                    )
            );
        }

        PixelShift pixelShift =
                shift.get();

        /*
         * ВАЖНО:
         *
         * Не создаём RenderGrid заново из target Viewport.
         *
         * Продолжаем стабильную render-grid старого frame
         * через integer offset. Именно это гарантирует
         * bit-for-bit одинаковые координаты reusable pixels.
         */
        RenderGrid targetGrid =
                sourceFrame.renderGrid()
                        .shifted(
                                pixelShift
                        );

        RenderFrame targetFrame =
                RenderFrame.create(
                        targetRequest,
                        targetGrid
                );

        int reusedPixels;

        if (sourceFrame.isComplete()) {

            reusedPixels =
                    copyReusableRectangle(
                            sourceFrame,
                            targetFrame,
                            pixelShift
                    );

        } else {

            reusedPixels =
                    copyReusablePixels(
                            sourceFrame,
                            targetFrame,
                            pixelShift
                    );
        }

        return FrameReuseResult.reused(
                targetFrame,
                pixelShift,
                reusedPixels
        );
    }

    private int copyReusableRectangle(
            RenderFrame sourceFrame,
            RenderFrame targetFrame,
            PixelShift shift
    ) {
        int width =
                sourceFrame.request()
                        .width();

        int height =
                sourceFrame.request()
                        .height();

        /*
         * Определяем прямоугольник source,
         * который останется внутри target
         * после shift.
         */
        int sourceXFrom =
                Math.max(
                        0,
                        -shift.dx()
                );

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(
                        0,
                        -shift.dy()
                );

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        /*
         * Кадры не пересекаются.
         */
        if (sourceXFrom >= sourceXTo
                || sourceYFrom >= sourceYTo) {
            return 0;
        }

        int reusableWidth =
                sourceXTo - sourceXFrom;

        int reusableHeight =
                sourceYTo - sourceYFrom;

        int targetXFrom =
                sourceXFrom + shift.dx();

        int targetYFrom =
                sourceYFrom + shift.dy();

        /*
         * Все pixels source frame гарантированно valid,
         * поскольку fast path вызывается только когда
         * sourceFrame.isComplete().
         */
        targetFrame.fractalData()
                .copyRegionFrom(
                        sourceFrame.fractalData(),
                        sourceXFrom,
                        sourceYFrom,
                        targetXFrom,
                        targetYFrom,
                        reusableWidth,
                        reusableHeight
                );

        /*
         * Весь overlap сразу можно пометить ready.
         *
         * ValidityMask сам внутри использует BitSet.set()
         * по каждой строке.
         */
        targetFrame.validity()
                .markReady(
                        new RenderRegion(
                                targetXFrom,
                                targetYFrom,
                                reusableWidth,
                                reusableHeight
                        )
                );

        return Math.multiplyExact(
                reusableWidth,
                reusableHeight
        );
    }

    private boolean canReuse(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        RenderRequest sourceRequest =
                sourceFrame.request();

        /*
         * Размер render grid должен совпадать.
         * Resize пока не поддерживаем.
         */
        if (sourceRequest.width()
                != targetRequest.width()) {
            return false;
        }

        if (sourceRequest.height()
                != targetRequest.height()) {
            return false;
        }

        /*
         * Данные, рассчитанные с другим maxIterations,
         * нельзя считать эквивалентными.
         */
        if (sourceRequest.maxIterations()
                != targetRequest.maxIterations()) {
            return false;
        }

        /*
         * Пока используем identity calculator.
         *
         * Это автоматически запрещает reuse,
         * например, после Mandelbrot -> Julia.
         */
        if (sourceRequest.calculator()
                != targetRequest.calculator()) {
            return false;
        }

        /*
         * Zoom пока не поддерживаем.
         *
         * Для partial-pan scale должен быть тем же.
         */
        return Double.compare(
                sourceRequest.viewport().scale(),
                targetRequest.viewport().scale()
        ) == 0;
    }

    private Optional<PixelShift> calculatePixelShift(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        Viewport sourceViewport =
                sourceFrame.request()
                        .viewport();

        Viewport targetViewport =
                targetRequest.viewport();

        RenderGrid grid =
                sourceFrame.renderGrid();

        /*
         * Семантика PixelShift:
         *
         * targetX = sourceX + shiftX
         * targetY = sourceY + shiftY
         *
         *
         * X:
         *
         * target viewport ушёл влево по complex plane
         * -> старое изображение визуально ушло вправо
         * -> shiftX положительный.
         */
        double rawShiftX =
                (sourceViewport.centerReal()
                        - targetViewport.centerReal())
                        / grid.realStep();

        /*
         * Y:
         *
         * imaginary axis направлена вверх,
         * экранный Y — вниз.
         */
        double rawShiftY =
                (targetViewport.centerImaginary()
                        - sourceViewport.centerImaginary())
                        / grid.imaginaryStep();

        Optional<Integer> shiftX =
                toIntegerShift(
                        rawShiftX
                );

        Optional<Integer> shiftY =
                toIntegerShift(
                        rawShiftY
                );

        if (shiftX.isEmpty()
                || shiftY.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                new PixelShift(
                        shiftX.get(),
                        shiftY.get()
                )
        );
    }

    private Optional<Integer> toIntegerShift(
            double value
    ) {
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }

        long rounded =
                Math.round(value);

        if (rounded < Integer.MIN_VALUE
                || rounded > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        /*
         * После snapToRenderGrid обычно видим что-то вроде:
         *
         * 340.000000000001
         *
         * Это допустимая floating-point погрешность.
         *
         * Настоящий fractional pan:
         *
         * 340.17
         *
         * будет отклонён.
         */
        if (Math.abs(value - rounded)
                > INTEGER_SHIFT_EPSILON) {
            return Optional.empty();
        }

        return Optional.of(
                (int) rounded
        );
    }

    private int copyReusablePixels(
            RenderFrame sourceFrame,
            RenderFrame targetFrame,
            PixelShift shift
    ) {
        int width =
                sourceFrame.request()
                        .width();

        int height =
                sourceFrame.request()
                        .height();

        /*
         * Ищем часть source frame, которая после shift
         * останется внутри target frame.
         */
        int sourceXFrom =
                Math.max(
                        0,
                        -shift.dx()
                );

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(
                        0,
                        -shift.dy()
                );

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        /*
         * Кадры вообще не пересекаются.
         */
        if (sourceXFrom >= sourceXTo
                || sourceYFrom >= sourceYTo) {
            return 0;
        }

        FractalData sourceData =
                sourceFrame.fractalData();

        FractalData targetData =
                targetFrame.fractalData();

        ValidityMask sourceValidity =
                sourceFrame.validity();

        ValidityMask targetValidity =
                targetFrame.validity();

        int reusedPixels = 0;

        for (int sourceY = sourceYFrom;
             sourceY < sourceYTo;
             sourceY++) {

            int targetY =
                    sourceY + shift.dy();

            /*
             * Собираем соседние valid pixels одной строки
             * в RenderRegion.
             *
             * Это лучше, чем вызывать markReady()
             * для каждого отдельного пикселя.
             */
            int runStartTargetX = -1;

            for (int sourceX = sourceXFrom;
                 sourceX < sourceXTo;
                 sourceX++) {

                int targetX =
                        sourceX + shift.dx();

                if (sourceValidity.isReady(
                        sourceX,
                        sourceY
                )) {

                    /*
                     * target <- source
                     */
                    targetData.copyPixelFrom(
                            sourceData,
                            sourceX,
                            sourceY,
                            targetX,
                            targetY
                    );

                    reusedPixels++;

                    if (runStartTargetX < 0) {
                        runStartTargetX =
                                targetX;
                    }

                } else if (runStartTargetX >= 0) {

                    /*
                     * Закончился contiguous run
                     * готовых пикселей.
                     */
                    markRunReady(
                            targetValidity,
                            runStartTargetX,
                            targetX,
                            targetY
                    );

                    runStartTargetX = -1;
                }
            }

            /*
             * Ready-run мог закончиться ровно
             * на правой границе overlap.
             */
            if (runStartTargetX >= 0) {

                int targetXTo =
                        sourceXTo
                                + shift.dx();

                markRunReady(
                        targetValidity,
                        runStartTargetX,
                        targetXTo,
                        targetY
                );
            }
        }

        return reusedPixels;
    }

    private void markRunReady(
            ValidityMask validity,
            int xFrom,
            int xTo,
            int y
    ) {
        if (xFrom >= xTo) {
            return;
        }

        validity.markReady(
                new RenderRegion(
                        xFrom,
                        y,
                        xTo - xFrom,
                        1
                )
        );
    }
}