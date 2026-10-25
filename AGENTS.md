# Antigravity Agent Guidelines for TruyenCloud

## Code Modularity & Single Responsibility Principle (SRP)

All code written or refactored in this repository MUST comply with the **Code Modularity Rule**:
- See detailed rule at: `.agents/rules/code-modularity.md`

### Hard Rules:
1. **File Size Budget**: **Strict maximum 350 lines** per source file (`.kt`, `.py`, `.swift`). Warning threshold at 250 lines.
2. **Never create monolithic "God Files"**:
   - Icons must be categorized into sub-files under `components/icons/` (`NavigationIcons.kt`, `ReaderIcons.kt`, `AudioIcons.kt`, `CloudStorageIcons.kt`, etc.) extending `TruyenCloudIcons`.
   - UI Screens (`*Screen.kt`) only orchestrate layout; sheets, dialogs, and complex subviews must each reside in their own dedicated files.
   - ViewModels over 350 lines must delegate domain logic to specialized controllers/coordinators.
   - Python services must separate prompt templates, external SDK clients, and worker queues.
3. **Verification**: Always run `bash tools/check_file_budget.sh` before finalizing any task.
