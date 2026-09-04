param(
    [Parameter(Mandatory = $true)]
    [string]$LauncherDirectory
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class McacManifestResource {
    private const uint LoadLibraryAsDataFile = 0x00000002;
    private static readonly IntPtr ManifestType = (IntPtr)24;
    private static readonly IntPtr ManifestName = (IntPtr)1;
    private const ushort EnglishUnitedStates = 1033;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryEx(string fileName, IntPtr file, uint flags);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr FindResourceEx(
        IntPtr module, IntPtr type, IntPtr name, ushort language);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LockResource(IntPtr resource);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint SizeofResource(IntPtr module, IntPtr resource);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FreeLibrary(IntPtr module);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr BeginUpdateResource(string fileName, bool deleteExistingResources);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool UpdateResource(
        IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EndUpdateResource(IntPtr update, bool discard);

    public static string ReadManifest(string fileName) {
        IntPtr module = LoadLibraryEx(fileName, IntPtr.Zero, LoadLibraryAsDataFile);
        if (module == IntPtr.Zero) {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        try {
            IntPtr resource = FindResourceEx(
                module, ManifestType, ManifestName, EnglishUnitedStates);
            if (resource == IntPtr.Zero) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            uint size = SizeofResource(module, resource);
            IntPtr data = LockResource(LoadResource(module, resource));
            if (data == IntPtr.Zero || size == 0) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            byte[] bytes = new byte[size];
            Marshal.Copy(data, bytes, 0, checked((int)size));
            return new UTF8Encoding(false, true).GetString(bytes);
        } finally {
            FreeLibrary(module);
        }
    }

    public static void WriteManifest(string fileName, string manifest) {
        byte[] bytes = new UTF8Encoding(false).GetBytes(manifest);
        IntPtr update = BeginUpdateResource(fileName, false);
        if (update == IntPtr.Zero) {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        bool committed = false;
        try {
            if (!UpdateResource(update, ManifestType, ManifestName,
                    EnglishUnitedStates, bytes, checked((uint)bytes.Length))) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (!EndUpdateResource(update, false)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            committed = true;
        } finally {
            if (!committed) {
                EndUpdateResource(update, true);
            }
        }
    }
}
'@

$activeCodePage = '<activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>'
$closingWindowsSettings = '</asmv3:windowsSettings>'

$directory = (Resolve-Path -LiteralPath $LauncherDirectory).Path
$launchers = @('mcac.exe', 'mcac-cli.exe', 'runtime-app.exe')

foreach ($launcher in $launchers) {
    $candidate = Join-Path $directory $launcher
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Expected jpackage launcher is missing: $candidate"
    }
    $path = (Resolve-Path -LiteralPath $candidate).Path
    $item = Get-Item -LiteralPath $path
    $wasReadOnly = $item.IsReadOnly
    try {
        if ($wasReadOnly) {
            $item.IsReadOnly = $false
        }
        $manifest = [McacManifestResource]::ReadManifest($path)
        if ($manifest -match '<activeCodePage\b') {
            if ($manifest -notmatch '<activeCodePage\b[^>]*>UTF-8</activeCodePage>') {
                throw "Launcher manifest has an unexpected activeCodePage setting: $path"
            }
            continue
        }
        if (-not $manifest.Contains($closingWindowsSettings)) {
            throw "Launcher manifest has no Windows settings section: $path"
        }
        $patched = $manifest.Replace(
            $closingWindowsSettings,
            $activeCodePage + $closingWindowsSettings)
        [McacManifestResource]::WriteManifest($path, $patched)
        if ([McacManifestResource]::ReadManifest($path) -notmatch '<activeCodePage\b[^>]*>UTF-8</activeCodePage>') {
            throw "Launcher UTF-8 manifest update was not persisted: $path"
        }
    }
    finally {
        if ($wasReadOnly) {
            (Get-Item -LiteralPath $path).IsReadOnly = $true
        }
    }
}

Write-Output "UTF-8 process manifest applied to $($launchers.Count) Windows launchers."
