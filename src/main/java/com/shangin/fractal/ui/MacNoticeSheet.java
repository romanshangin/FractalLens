package com.shangin.fractal.ui;

import javafx.stage.Window;
import java.util.function.Consumer;

/** Native NSAlert sheets and a nonmodal AppKit help panel. */
final class MacNoticeSheet extends MacParameterSheet<Boolean> {
    private final Consumer<Boolean> result;
    private MacNoticeSheet(Window owner, Consumer<Boolean> accepted) {
        super(owner, accepted);
        result = accepted;
    }

    @Override void cancelled() { result.accept(false); }

    static MacNoticeSheet show(Window owner, String title, String message,
                               boolean confirmation, boolean modeless, Consumer<Boolean> accepted) {
        var sheet = new MacNoticeSheet(owner, accepted);
        sheet.showNoticeNative(title, message, confirmation, modeless);
        return sheet;
    }

    @Override int invalidField(String first, String second) { return 0; }
    @Override Boolean parse(String first, String second) { return Boolean.TRUE; }
}
