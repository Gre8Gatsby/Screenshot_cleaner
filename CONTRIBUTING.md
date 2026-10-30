# Contributing to Screenshot Auto-Expirer

Thank you for your interest in contributing to **Screenshot Auto-Expirer**! We are committed to keeping this project open-source, ultra-small, privacy-respecting, and community-driven.

---

## 🧭 How You Can Help

1. **Expanding Pattern Detection**:
   - Add new keywords and regexes for OTPs, 2FA, regional payment systems (Pix, Mercado Pago, Swish, Venmo, CashApp), or transit systems (boarding passes, train tickets) in [`rust-core/src/patterns.rs`](rust-core/src/patterns.rs).
2. **Localization**:
   - Help translate strings in `app/src/main/res/values/strings.xml` into other languages (e.g. `values-es`, `values-hi`, `values-fr`, `values-de`).
3. **Testing on Different Devices**:
   - Test background execution on OEM variants with aggressive battery management (Xiaomi HyperOS/MIUI, Samsung OneUI, OnePlus OxygenOS, Oppo ColorOS) and document workarounds.
4. **Performance & Size Optimization**:
   - Propose further size reductions in the Rust core or Dalvik bytecode.

---

## 🛠 Development Workflow

### 1. Fork & Branch
```bash
git clone https://github.com/Gre8Gatsby/Screenshot_cleaner.git
cd screenshot-auto-expirer
git checkout -b feature/your-feature-name
```

### 2. Rust Code Guidelines
- Follow standard Rust naming conventions.
- Keep the binary size budget in mind: avoid adding heavy crates unless strictly necessary.
- Format code before submitting:
  ```bash
  cd rust-core
  cargo fmt --all
  ```
- Run the linter and tests:
  ```bash
  cargo clippy -- -D warnings
  cargo test
  ```
  All tests must pass with zero warnings.

### 3. Android / Kotlin Guidelines
- Use idiomatic Kotlin.
- Avoid introducing unnecessary third-party dependencies that bloat APK size.
- Ensure all resources (`strings.xml`, `colors.xml`) are properly externalized.

### 4. Commit Messages
We follow the [Conventional Commits](https://www.conventionalcommits.org/) convention:
- `feat(patterns): add Pix and Mercado Pago payment regexes`
- `fix(jni): handle null pointer edge case during string decoding`
- `docs(readme): add troubleshooting steps for Xiaomi battery saver`
- `test(classifier): add unit tests for boarding pass PNR matching`

---

## 🧪 Testing Your Changes

Before opening a pull request:

1. **Rust Tests**:
   ```bash
   cd rust-core && cargo test
   ```
2. **Device / Emulator Testing**:
   - Verify that your changes work on an actual device or emulator running Android 10+ (API 29+).
   - Use the test snippets provided in the [README](README.md#-sample-text-snippets-for-quick-testing) to verify classification.

---

## 📬 Submitting a Pull Request

1. Push your branch to GitHub:
   ```bash
   git push origin feature/your-feature-name
   ```
2. Open a Pull Request against the `main` branch.
3. Describe what your PR accomplishes, why the change is needed, and any test results.
4. Our automated GitHub Actions CI will verify that:
   - Rust unit & integration tests pass cleanly.
   - The release APK builds successfully.

Thank you for contributing to keeping Android galleries clean and secure! 🚀
