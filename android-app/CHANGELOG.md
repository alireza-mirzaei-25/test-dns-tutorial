# Changelog

All notable changes to the DNSTT VPN Client Android app will be documented in this file.

## [1.2.0] - 2025-01-21

### Added

- **Global DNS Display:** Now displays all 3,956 DNS servers from dns_servers.txt in Global DNS tab
  - Previously only showed 8 hardcoded popular DNS servers
  - All public DNS servers from the asset file are now accessible
  - Efficient loading with RecyclerView pattern
  - DNS servers displayed with format "DNS Server N" and their IP:port address

- **DNS Import Feature:** Added ability to import DNS servers from text files
  - Import menu item in Custom DNS tab with icon
  - Supports plain IP format (e.g., `1.1.1.1`) and IP:port format (e.g., `8.8.8.8:53`)
  - Automatic port appending (`:53`) for plain IPs
  - IP address used as DNS name for imported entries
  - Validation with detailed error messages showing line numbers
  - Import replaces current list contents
  - File format: One DNS per line, supports comments (`#`) and empty lines
  - File picker integration for easy file selection

### Improved

- **DNS Card Button Styling:** Enhanced button readability and layout
  - Increased button font size from ~14sp to 16sp for all action buttons (Edit, Delete, Test, Select)
  - Removed purple border from Test button (now uses app's primary color)
  - Restructured button layout into two rows to prevent overflow:
    - Top row: Edit, Delete buttons (left-aligned)
    - Bottom row: Test, Select buttons (right-aligned)
  - All four buttons now fit properly without text cutoff on all screen sizes

- **Configure DNS Button:** Improved main screen button styling
  - Larger button (56dp height)
  - Bold text (15sp) for better visibility
  - Enhanced icon sizing (24dp) and positioning
  - Proper Material Design styling with primary color background

- **DNS Source Dropdown:** Enhanced dropdown functionality
  - Non-editable dropdown-only mode
  - Proper click handler for dropdown display
  - Refreshes automatically when returning from configuration
  - Shows all available DNS sources (Global + custom lists)

### Fixed

- **Material3 Theme Issues:**
  - Fixed theme attribute resolution errors causing purple borders
  - Replaced theme attributes with direct color references for compatibility
  - Fixed button border colors to match app theme

- **Button Overflow:**
  - Fixed text overflow when all four action buttons are visible
  - Implemented two-row layout to accommodate all buttons
  - No more text cutoff or ellipsis on button labels

- **Memory Management:**
  - Fixed memory leaks in MainActivity and DnsttVpnService
  - Implemented WeakReference pattern for UI callbacks
  - Added proper Handler cleanup with removeCallbacksAndMessages(null)
  - Proper thread lifecycle management (start → interrupt → join → null)

- **Null Safety:**
  - Fixed null pointer exceptions throughout codebase
  - Added comprehensive null checks before UI operations
  - Protected shared state with synchronized access patterns

- **DNS Search Improvements:**
  - Added timeout mechanism (60 seconds) for DNS resolver search
  - Added cancel functionality for DNS search
  - Fixed state synchronization between UI and connection states
  - Implemented volatile flags for thread-safe state management

### Technical Details

**Modified Files:**
- `DnsConfigManager.java` - Refactored getGlobalDnsConfigs() to load from dns_servers.txt asset file
- `CustomDnsFragment.java` - Added import functionality with file picker, validation, and error handling
- `MainActivity.java` - Added DNS source dropdown, improved state management, fixed memory leaks
- `DnsttVpnService.java` - Fixed memory leaks with WeakReference pattern
- `item_dns_config.xml` - Two-row button layout with improved styling
- `activity_main.xml` - Enhanced Configure DNS button and dropdown styling

**Created Files:**
- `ic_import.xml` - Material Design import icon for import functionality

**Key Improvements:**
- Enhanced DNS validation to support multiple formats (IP only, IP:port)
- Improved error handling with specific validation messages and line numbers
- Background thread processing for file import to prevent UI freeze
- Proper exception handling and user feedback via Toasts and dialogs

## [1.1.0] - Previous Version

- Initial release with basic VPN functionality
- Auto DNS and Manual DNS features
- Basic DNS configuration management
- Support for custom DNS servers
- VPN connection management

---

## File Format Examples

### DNS Import File Format

The import feature accepts text files with one DNS server per line:

```
1.0.0.1
1.1.1.1
103.111.69.1
103.111.69.10
8.8.8.8:53
9.9.9.9:853
# This is a comment
# Lines starting with # are ignored
```

**Format Rules:**
- Plain IP addresses: `1.1.1.1` (port `:53` will be added automatically)
- IP with custom port: `8.8.8.8:853`
- Comments: Lines starting with `#` are ignored
- Empty lines are ignored
- Invalid IPs show error messages with line numbers

### Features Summary

- **Global DNS:** 3,956 public DNS servers from dns_servers.txt
- **Custom DNS:** User-managed DNS lists with import capability
- **Auto DNS:** Automatically finds working DNS servers from selected source
- **Manual DNS:** Use specific DNS server address
- **DNS Testing:** Test connectivity and latency for any DNS server
- **DNS Selection:** Quick selection from Global or Custom lists

## Development Notes

### Version Numbering
This project follows [Semantic Versioning](https://semver.org/):
- **Major.Minor.Patch** (e.g., 1.2.0)
- Major: Incompatible API changes
- Minor: New features (backward compatible)
- Patch: Bug fixes (backward compatible)

### Building the App
```bash
./gradlew assembleDebug
```

### Installing on Device
```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

### Architecture
- **MVVM Pattern** for UI layer
- **Repository Pattern** for data management
- **Material Design 3** components for UI
- **RecyclerView** with ViewHolder pattern for efficient lists
- **SharedPreferences + Gson** for data persistence
- **Go Mobile** for VPN functionality (gomobile AAR)
