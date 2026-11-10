/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{html,ts}"
  ],
  theme: {
    extend: {
      colors: {
        capitec: {
          blue: '#00529B',
          light: '#0072CE',
          dark: '#00386B',
          red: '#E63946'
        }
      }
    }
  },
  plugins: []
};

