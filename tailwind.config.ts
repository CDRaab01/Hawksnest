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
    },
  },
  plugins: [],
} satisfies Config;
