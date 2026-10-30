# Go to destinations

Go to lists bookmarks for the selected Fractal. Each replaces center/vertical span
in one render using decimal-string coordinates and explicit spans for reproducible
framing. Displayed zoom = fitted home height / bookmark span, so aspect ratio matters;
source magnifications are not universally interchangeable.

## Sources (accessed 2026-09-11)

- Mandelbrot: [The Mandelbrot Map, coordinate data](https://jolinton.co.uk/Books/The_Mandelbrot_Map/Text.pdf)
  supplies Elephant Valley (0.271787, -0.005557), 1150×, and Seahorse
  (-0.741444, 0.168525), 150×. Spans adapted to 2.4 / magnification.
  [Institute for Figuring](https://theiff.org/about/mset.html) describes Seahorse Valley;
  its wider overview is framed at (-0.75, 0.1), height 0.15.
- Julia: [The Delta Epsilon, volume 8, Figure 9](https://thedeltaepsilonmcgill.github.io/pdfs/DE8.pdf)
  uses exactly our c = -0.8 + 0.156i. Centers and spans are read from panels b–d:
  (0, -0.6), 0.4; (-0.1, -0.45), 0.14; (-0.035, -0.493), 0.014.
  Last two heights include framing margin for wider source panels. Names describe the views.
- Cubic Multibrot: [Russell Cottrell's Multibrot explorer](https://www.russellcottrell.com/fractalsEtc/multibrot.htm)
  recommends cusp regions and supports degree 3. It does not publish these bookmarks:
  these are locally derived views, not claimed as published named destinations.
  Right cusp is c = 2/(3 sqrt(3)); the period-2 center c = i satisfies
  0 → i → 0; upper antenna framing uses (0, 1.08). Spans are locally selected.
- Burning Ship: [Ice Fractal](https://icefractal.com/articles/secrets-of-the-burning-ship/)
  identifies the little ship near -1.75 and explains orientation conventions.
  [Josh Brew's explorer](https://codepen.io/joshbrew/pen/dPoZqpa) supplies
  (-1.7443359375, -0.017451171875).
  [Theory.org](https://theory.org/fracdyn/burningship/) supplies (-0.75, -1.15).
  All imaginary coordinates are reflected for BurningShipFormula's upright convention.
  Heights 0.09, 0.008, and 0.3 are locally chosen framing.
- Tricorn: [Paul Bourke](https://paulbourke.net/fractals/tricorn/) lists
  (-1.25, 0), 4×; (-1.756, 0), 25×; (-1.9412, 0), 300×;
  (-0.85, 0.145), 30×. Heights use 2.5 / magnification, adapted to our home scale.

Regressions check each bookmark for non-uniform escape structure against its
formula and render the catalog as a contact sheet.
