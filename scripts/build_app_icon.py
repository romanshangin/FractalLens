#!/usr/bin/env python3
"""Build Java/ICNS fallback icons from unmasked artwork. Requires Pillow."""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design/app-icon/matte-square.png"
LEGACY_MASK = ROOT / "design/app-icon/macos-legacy-mask.png"
DESTINATION = ROOT / "src/main/resources/com/shangin/fractal/app/icons"
BACKGROUND = (217, 237, 248)
FOREGROUND = (16, 59, 117)


def icon_layers(artwork):
    # Extract the two-color generated symbol without redrawing its boundary.
    # Clamp tiny color noise in the otherwise flat interior and background.
    alpha = artwork.convert("RGB").getchannel("R").point(
        lambda red: round(max(0, min(1, ((217 - red) / (217 - 20) - 0.03) / 0.94)) * 255)
    )
    symbol = Image.new("RGBA", artwork.size, (*FOREGROUND, 255))
    symbol.putalpha(alpha)
    symbol = symbol.resize((1024, 1024), Image.Resampling.LANCZOS)
    background = Image.new("RGBA", (1024, 1024), (*BACKGROUND, 255))
    return symbol, background


def legacy_icon(artwork):
    # Java's Dock API and legacy ICNS consume a flattened image; they do not
    # apply Icon Composer's system mask. Keep this compatibility mask separate
    # from the full-bleed square artwork for a future layered native icon.
    mask = Image.open(LEGACY_MASK).convert("L")
    bounds = mask.getbbox()
    if bounds is None:
        raise ValueError("Empty legacy icon mask")
    left, top, right, bottom = bounds
    tile = artwork.convert("RGBA").resize((right - left, bottom - top), Image.Resampling.LANCZOS)
    icon = Image.new("RGBA", mask.size)
    icon.paste(tile, (left, top))
    icon.putalpha(mask)
    # Clear hidden RGB too, so previews that discard alpha don't reveal a square.
    clean = Image.new("RGBA", mask.size)
    clean.paste(icon, (0, 0), mask.point(lambda alpha: 255 if alpha else 0))
    return clean


def main():
    source = Image.open(SOURCE)
    symbol, background = icon_layers(source)
    symbol.save(SOURCE.parent / "mandelbrot.png")
    native_layer = SOURCE.parent / "FractalLens.icon/Assets/mandelbrot.png"
    if native_layer.parent.is_dir():
        symbol.save(native_layer)
    flat = Image.alpha_composite(background, symbol)
    flat.save(SOURCE.parent / "flat-square.png")
    icon = legacy_icon(flat)
    DESTINATION.mkdir(parents=True, exist_ok=True)
    variants = [icon.resize((size, size), Image.Resampling.LANCZOS)
                for size in (32, 64, 128, 256, 512, 1024)]
    variants[-1].save(DESTINATION / "fractallens.png")
    variants[-1].save(DESTINATION / "FractalLens.icns", append_images=variants)
    print(f"Saved PNG and ICNS to {DESTINATION}")


if __name__ == "__main__":
    main()
