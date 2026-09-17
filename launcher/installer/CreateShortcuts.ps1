# 创建桌面/开始菜单快捷方式，并写入 AppUserModelID，
# 使运行时窗口（java.exe 内 JNA 设置同名 AUMID）与快捷方式正确关联，
# 任务栏右键显示"固定到任务栏/结束任务"，固定后可正常重启。
param(
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$WorkDir,
    [Parameter(Mandatory=$true)][string]$Aumid,
    [Parameter(Mandatory=$true)][string]$Name
)

$ErrorActionPreference = "Stop"

$csharp = @"
using System;
using System.Runtime.InteropServices;

namespace YunxInstall {
    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    public struct PropertyKey {
        public Guid fmtid;
        public int pid;
        public PropertyKey(Guid f, int p) { fmtid = f; pid = p; }
    }

    [StructLayout(LayoutKind.Explicit, Size = 16)]
    public struct PropVariant {
        [FieldOffset(0)] public ushort vt;
        [FieldOffset(2)] public ushort wReserved1;
        [FieldOffset(4)] public ushort wReserved2;
        [FieldOffset(6)] public ushort wReserved3;
        [FieldOffset(8)] public IntPtr pwszVal;
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPropertyStore {
        uint GetCount(out uint cProps);
        uint GetAt(uint iProp, out PropertyKey pkey);
        uint GetValue(ref PropertyKey key, out PropVariant pv);
        uint SetValue(ref PropertyKey key, ref PropVariant pv);
        uint Commit();
    }

    [ComImport, Guid("0000010B-0000-0000-C000-000000000046"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPersistFile {
        void GetClassID(out Guid pClassID);
        void IsDirty();
        void Load([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode);
        void Save([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, [MarshalAs(UnmanagedType.Bool)] bool fRemember);
        void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string pszFileName);
        void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string ppszFileName);
    }

    public static class Setup {
        static readonly PropertyKey AppUserModelIdKey = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5);

        public static void Create(string lnkPath, string target, string workDir, string aumid) {
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            dynamic shell = Activator.CreateInstance(shellType);
            var sc = shell.CreateShortcut(lnkPath);
            sc.TargetPath = target;
            sc.WorkingDirectory = workDir;
            sc.IconLocation = target + ",0";
            sc.Description = "YunX Desktop Fork";
            sc.Save();

            Type t = Type.GetTypeFromCLSID(new Guid("00021401-0000-0000-C000-000000000046"));
            object slObj = Activator.CreateInstance(t);
            IPersistFile pf = (IPersistFile)slObj;
            pf.Load(lnkPath, 0);
            IPropertyStore store = (IPropertyStore)slObj;
            PropVariant pv = new PropVariant();
            pv.vt = 31; // VT_LPWSTR
            pv.pwszVal = Marshal.StringToCoTaskMemUni(aumid);
            PropertyKey key = AppUserModelIdKey;
            int hr = (int)store.SetValue(ref key, ref pv);
            store.Commit();
            pf.Save(lnkPath, true);
            Marshal.FreeCoTaskMem(pv.pwszVal);
        }
    }
}
"@

Add-Type -TypeDefinition $csharp -Language CSharp

function New-Link($lnkPath) {
    $dir = Split-Path $lnkPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [YunxInstall.Setup]::Create($lnkPath, $Target, $WorkDir, $Aumid)
    Write-Host "Created: $lnkPath"
}

$desktop = [Environment]::GetFolderPath("Desktop")
New-Link (Join-Path $desktop "$Name.lnk")

$startMenu = Join-Path ([Environment]::GetFolderPath("Programs")) "$Name"
New-Link (Join-Path $startMenu "$Name.lnk")
