import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // These effects synchronize initial/streamed backend state into the UI.
      'react-hooks/set-state-in-effect': 'off',
      // useResource intentionally accepts a caller-provided dependency vector.
      'react-hooks/use-memo': 'off',
      'react-hooks/exhaustive-deps': 'off',
      // Context modules export both their Provider component and its access hook.
      'react-refresh/only-export-components': 'off',
    },
  },
)
