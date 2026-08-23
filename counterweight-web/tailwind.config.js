/**
 * Colours are CSS custom properties, not Tailwind literals.
 *
 * The till has three theme states — explicit light, explicit dark, and the
 * unstamped default that follows the OS. Tokens are the only way all three
 * resolve as a set; a utility class carrying a literal hex works in one theme
 * and quietly fails in the other.
 */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ground:    "var(--ground)",
        surface:   "var(--surface)",
        surface2:  "var(--surface-2)",
        ink:       "var(--ink)",
        inksoft:   "var(--ink-soft)",
        inkfaint:  "var(--ink-faint)",
        line:      "var(--line)",
        linesoft:  "var(--line-soft)",
        accent:    "var(--accent)",
        accent2:   "var(--accent-2)",
        accentink: "var(--accent-ink)",
        accentwash:"var(--accent-wash)",
        warn:      "var(--warn)",
        warnwash:  "var(--warn-wash)",
        danger:    "var(--danger)",
        dangerwash:"var(--danger-wash)",
        good:      "var(--good)",
        goodwash:  "var(--good-wash)",
      },
      fontFamily: {
        sans: ["IBM Plex Sans", "Segoe UI", "system-ui", "sans-serif"],
        mono: ["IBM Plex Mono", "Cascadia Mono", "Consolas", "monospace"],
      },
      borderRadius: { DEFAULT: "3px", sm: "2px" },
    },
  },
  plugins: [],
};
