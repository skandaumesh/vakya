"""Generate Android launcher icons from logo.png at all required densities."""
import os
from PIL import Image

LOGO = os.path.join(os.path.dirname(__file__), "logo.png")
RES_DIR = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")

# Standard launcher icon sizes per density bucket
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Foreground layer for adaptive icons (108dp, with 18dp safe zone = 72dp visible)
ADAPTIVE_DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def crop_to_square(img: Image.Image) -> Image.Image:
    """Crop the image to a centered square."""
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return img.crop((left, top, left + side, top + side))


def create_foreground(img: Image.Image, size: int) -> Image.Image:
    """Create adaptive icon foreground: logo centered on transparent 108dp canvas."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # The visible area is the inner 66.67% (72/108), so place logo within that
    safe_size = int(size * 72 / 108)
    logo_resized = img.resize((safe_size, safe_size), Image.LANCZOS)
    offset = (size - safe_size) // 2
    canvas.paste(logo_resized, (offset, offset), logo_resized if logo_resized.mode == "RGBA" else None)
    return canvas


def main():
    img = Image.open(LOGO)
    square = crop_to_square(img)

    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES_DIR, density)
        os.makedirs(out_dir, exist_ok=True)

        # Legacy launcher icon
        icon = square.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(out_dir, "ic_launcher.png"))
        icon.save(os.path.join(out_dir, "ic_launcher_round.png"))
        print(f"  {density}/ic_launcher.png ({size}x{size})")

    for density, size in ADAPTIVE_DENSITIES.items():
        out_dir = os.path.join(RES_DIR, density)
        os.makedirs(out_dir, exist_ok=True)

        # Foreground layer for adaptive icon
        fg = create_foreground(square, size)
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        print(f"  {density}/ic_launcher_foreground.png ({size}x{size})")

    print("\nDone! Icon PNGs generated in all density buckets.")


if __name__ == "__main__":
    main()
