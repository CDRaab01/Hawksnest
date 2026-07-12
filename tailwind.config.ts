import type { Config } from "tailwindcss";

/**
 * Hawksnest theme = Spotter's PULSE tokens, ported to Tailwind.
 * Values are sourced from Spotter's ui/theme (Pulse.kt / Color.kt / Dimens.kt /
 * Shape.kt / Motion.kt). Components reference these tokens only — never raw hex.
 * Concrete values live as CSS custom properties in src/theme/tokens.css; this
 * config maps them to Tailwind utility names.
 */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        bg: "var(--bg)",
        panel: "var(--panel)",
        "panel-high": "var(--panel-high)",
        ink: {
          DEFAULT: "var(--text)",
          dim: "var(--text-dim)",
          faint: "var(--text-faint)",
        },
        // PULSE channels — each owns base / dim (container) / on (content).
        effort: {
          DEFAULT: "var(--effort)",
          dim: "var(--effort-dim)",
          on: "var(--effort-on)",
        },
        recovery: {
          DEFAULT: "var(--recovery)",
          dim: "var(--recovery-dim)",
          on: "var(--recovery-on)",
        },
        strength: {
          DEFAULT: "var(--strength)",
          dim: "var(--strength-dim)",
          on: "var(--strength-on)",
        },
        streak: {
          DEFAULT: "var(--streak)",
          dim: "var(--streak-dim)",
          on: "var(--streak-on)",
        },
        // Hairlines as colors too (not just borderColor) so gradients/fills can
        // use them — e.g. the skeleton shimmer sweeps in hairline-strong.
        hairline: {
          DEFAULT: "var(--hairline)",
          strong: "var(--hairline-strong)",
        },
      },
      borderColor: {
        hairline: "var(--hairline)",
        "hairline-strong": "var(--hairline-strong)",
      },
      backgroundImage: {
        hero: "linear-gradient(135deg, var(--hero-from), var(--hero-to))",
        energy: "linear-gradient(135deg, var(--energy-from), var(--energy-to))",
      },
      borderRadius: {
        // Tightened to instrument-bezel radii.
        sm: "8px",
        DEFAULT: "12px",
        md: "12px",
        lg: "16px",
      },
      spacing: {
        xs: "4px",
        sm: "8px",
        md: "12px",
        lg: "16px",
        xl: "24px",
        xxl: "32px",
      },
      fontFamily: {
        display: ['"Space Grotesk"', "system-ui", "sans-serif"],
        body: ["Inter", "system-ui", "sans-serif"],
        mono: ['"JetBrains Mono"', "ui-monospace", "monospace"],
      },
      fontSize: {
        // UI scale (minor third): 12 / 14 / 17 / 20 / 24 / 29 (+ display 35 / 42).
        caption: ["12px", { lineHeight: "16px" }],
        body: ["14px", { lineHeight: "20px" }],
        "body-lg": ["17px", { lineHeight: "24px" }],
        title: ["20px", { lineHeight: "26px" }],
        headline: ["24px", { lineHeight: "30px" }],
        display: ["29px", { lineHeight: "34px" }],
        "display-lg": ["35px", { lineHeight: "40px" }],
        // Data (mono) scale.
        "data-sm": ["20px", { lineHeight: "26px" }],
        "data-md": ["32px", { lineHeight: "36px", letterSpacing: "-0.5px" }],
        "data-lg": ["44px", { lineHeight: "48px", letterSpacing: "-1px" }],
        "data-xl": ["60px", { lineHeight: "64px", letterSpacing: "-1.5px" }],
      },
      transitionDuration: {
        fast: "120ms",
        standard: "240ms",
        emphasized: "400ms",
        data: "600ms",
      },
      transitionTimingFunction: {
        ease: "cubic-bezier(0.2, 0, 0, 1)",
        decel: "cubic-bezier(0.05, 0.7, 0.1, 1)",
      },
      keyframes: {
        // One low-amplitude sweep across a skeleton surface (hairline-strong, not
        // a candy gradient) — the loading texture for camera tiles + charts.
        shimmer: {
          "0%": { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(100%)" },
        },
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        // The bolt "thunk": a physical settle on the lock icon when HA's echo
        // confirms — quick press past center, small rebound, rest.
        thunk: {
          "0%": { transform: "scale(1) rotate(0deg)" },
          "35%": { transform: "scale(1.18) rotate(-6deg)" },
          "65%": { transform: "scale(0.94) rotate(2deg)" },
          "100%": { transform: "scale(1) rotate(0deg)" },
        },
        // One-shot channel wash over a card at the settle moment, fading out.
        "settle-flash": {
          from: { opacity: "0.16" },
          to: { opacity: "0" },
        },
      },
      animation: {
        shimmer: "shimmer 1.5s cubic-bezier(0.2, 0, 0, 1) infinite",
        "fade-in": "fade-in 400ms cubic-bezier(0.05, 0.7, 0.1, 1) both",
        thunk: "thunk 400ms cubic-bezier(0.05, 0.7, 0.1, 1) both",
        "settle-flash": "settle-flash 700ms cubic-bezier(0.2, 0, 0, 1) both",
      },
    },
  },
  plugins: [],
} satisfies Config;
