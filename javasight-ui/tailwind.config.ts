import type { Config } from 'tailwindcss'

const config: Config = {
  darkMode: ["class"],
  content: [
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  prefix: "",
  theme: {
    container: {
      center: true,
      padding: '2rem',
      screens: {
        '2xl': '1400px'
      }
    },
    extend: {
      colors: {
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        background: {
          muted: '#f9fafb',
          subtle: '#f3f4f6',
          DEFAULT: '#ffffff',
          emphasis: '#374151'
        },
        foreground: 'hsl(var(--foreground))',
        primary: {
          light: '#9b87f5',
          DEFAULT: '#6366f1',
          dark: '#4338ca',
          foreground: '#ffffff'
        },
        secondary: {
          light: '#FEC6A1',
          DEFAULT: '#f97316',
          dark: '#ea580c',
          foreground: '#ffffff'
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))'
        },
        muted: {
          DEFAULT: '#f3f4f6',
          foreground: '#6b7280'
        },
        accent: {
          DEFAULT: '#f3f4f6',
          foreground: '#374151'
        },
        popover: {
          DEFAULT: '#ffffff',
          foreground: '#374151'
        },
        card: {
          DEFAULT: '#ffffff',
          foreground: '#374151'
        },
        content: {
          subtle: '#9ca3af',
          DEFAULT: '#6b7280',
          emphasis: '#374151',
          strong: '#111827',
          inverted: '#ffffff'
        }
      },
      backgroundImage: {
        'gradient-subtle': 'linear-gradient(109.6deg, rgba(223,234,247,1) 11.2%, rgba(244,248,252,1) 91.1%)',
        'gradient-card': 'linear-gradient(to right, #ffffff 0%, #f3f4f6 100%)',
        'gradient-primary': 'linear-gradient(135deg, #9b87f5 0%, #6366f1 100%)',
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)'
      },
      boxShadow: {
        'tremor-input': '0 1px 2px 0 rgb(0 0 0 / 0.05)',
        'tremor-card': '0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)',
        'tremor-dropdown': '0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)'
      },
      keyframes: {
        'accordion-down': {
          from: { height: '0' },
          to: { height: 'var(--radix-accordion-content-height)' }
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: '0' }
        }
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out'
      }
    }
  },
  plugins: [require("tailwindcss-animate"), require("@tailwindcss/typography")],
} satisfies Config;

export default config 