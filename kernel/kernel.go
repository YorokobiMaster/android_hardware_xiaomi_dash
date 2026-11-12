// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package kernel

import (
	"fmt"
	"path/filepath"
	"strings"

	"android/soong/android"
	"github.com/google/blueprint/proptools"
)

var pctx = android.NewPackageContext("kleaf/soong/kernel")

type kernelProperties struct {
	Script       *string  `android:"path"`
	Inputs       []string `android:"path"`
	Source_files []string
	Source_dirs  []string
}

type kernelModule struct {
	android.ModuleBase
	properties kernelProperties
	image      android.Path
	installDir android.InstallPath
}

func init() {
	android.RegisterModuleType("kleaf_kernel", kernelFactory)
}

func kernelFactory() android.Module {
	m := &kernelModule{}
	m.AddProperties(&m.properties)
	android.InitAndroidArchModule(m, android.DeviceSupported, android.MultilibFirst)
	android.AddLoadHook(m, m.createModuleInstallers)
	return m
}

func (m *kernelModule) partitions(ctx android.EarlyModuleContext) []string {
	partitions := []string{"vendor_ramdisk", "vendor_dlkm"}
	if ctx.Config().VendorConfig("kleaf").Bool("system_dlkm") {
		partitions = append(partitions, "system_dlkm")
	}
	return partitions
}

func (m *kernelModule) installList(ctx android.EarlyModuleContext, partition string) []string {
	return strings.Fields(ctx.Config().VendorConfig("kleaf").String(partition + "_modules"))
}

func (m *kernelModule) createModuleInstallers(ctx android.LoadHookContext) {
	if ctx.Config().VendorConfig("kleaf").String("profile") == "" {
		ctx.AppendProperties(&struct{ Enabled *bool }{proptools.BoolPtr(false)})
		return
	}
	// Use Soong's registered native installer without importing its private Go package.
	factory := android.ModuleTypeFactories()["prebuilt_kernel_modules"]
	systemDlkm := ctx.Config().VendorConfig("kleaf").Bool("system_dlkm")
	for _, partition := range m.partitions(ctx) {
		load := strings.Fields(ctx.Config().VendorConfig("kleaf").String(partition + "_load"))
		if partition == "vendor_ramdisk" {
			load = append([]string{"ufs_wlun_bootstrap.ko"}, load...)
		}
		props := &struct {
			Name                  *string
			Srcs                  proptools.Configurable[[]string]
			Src_filenames_to_load []string
			Strip_debug_symbols   *bool
			System_dep            *string
			Vendor_ramdisk        *bool
			Vendor_dlkm_specific  *bool
			System_dlkm_specific  *bool
		}{
			Name:                  proptools.StringPtr("kernel_modules_" + partition),
			Srcs:                  android.NewSimpleConfigurable([]string{":" + ctx.ModuleName() + "{." + partition + "}"}),
			Src_filenames_to_load: load,
			Strip_debug_symbols:   proptools.BoolPtr(false),
		}
		switch partition {
		case "vendor_ramdisk":
			props.Vendor_ramdisk = proptools.BoolPtr(true)
		case "vendor_dlkm":
			props.Vendor_dlkm_specific = proptools.BoolPtr(true)
			if systemDlkm {
				props.System_dep = proptools.StringPtr(":kernel_modules_system_dlkm{.modules.zip}")
			}
		case "system_dlkm":
			props.System_dlkm_specific = proptools.BoolPtr(true)
		}
		ctx.CreateModule(factory, props)
	}
}

// Split source dependencies by top-level directory to stay below Ninja's
// per-record deps-log limit. Kleaf retains ownership of its compilation cache.
func (m *kernelModule) sourceDeps(ctx android.ModuleContext) android.Paths {
	var inputs android.Paths
	i := 0
	for _, root := range m.properties.Source_dirs {
		entries, err := ctx.GlobWithDeps(filepath.Join(root, "*"), nil)
		if err != nil {
			ctx.ModuleErrorf("tracking %s: %s", root, err)
			continue
		}
		for _, entry := range entries {
			if !strings.HasSuffix(entry, "/") {
				inputs = append(inputs, android.PathForSource(ctx, entry))
				continue
			}
			stamp := android.PathForModuleOut(ctx, "source_deps", fmt.Sprintf("%d.timestamp", i))
			depfile := android.PathForModuleOut(ctx, "source_deps", fmt.Sprintf("%d.d", i))
			rule := android.NewRuleBuilder(pctx, ctx).SandboxDisabled()
			rule.Command().Text("touch").Output(stamp)
			rule.Command().BuiltTool("kernel_source_deps").
				FlagWithDepFile("-o ", depfile).FlagWithArg("-t ", stamp.String()).
				Text(strings.TrimSuffix(entry, "/"))
			rule.Build(fmt.Sprintf("kernel_source_dir_%d", i), "Kernel source dependencies")
			inputs = append(inputs, stamp)
			i++
		}
		// Git identity affects Kleaf's stamped release even if source bytes match.
		for _, pattern := range []string{"HEAD", "index", "packed-refs", "refs/heads/**/*"} {
			paths, err := ctx.GlobWithDeps(filepath.Join(root, ".git", pattern), nil)
			if err != nil {
				ctx.ModuleErrorf("tracking Git metadata: %s", err)
				continue
			}
			for _, path := range paths {
				if !strings.HasSuffix(path, "/") {
					inputs = append(inputs, android.PathForSource(ctx, path))
				}
			}
		}
	}
	return inputs
}

func (m *kernelModule) GenerateAndroidBuildActions(ctx android.ModuleContext) {
	config := ctx.Config().VendorConfig("kleaf")
	profile := config.String("profile")
	if profile != "normal" && profile != "strict" && profile != "official" {
		ctx.ModuleErrorf("unknown kernel profile %q", profile)
		return
	}
	inputs := m.sourceDeps(ctx)
	inputs = append(inputs, android.PathsForModuleSrc(ctx, m.properties.Inputs)...)
	for _, path := range m.properties.Source_files {
		inputs = append(inputs, android.PathForSource(ctx, path))
	}
	out := android.PathForModuleOut(ctx, profile)
	image := out.Join(ctx, "Image.lz4")
	configFile := out.Join(ctx, ".config")
	headers := out.Join(ctx, "kernel-uapi-headers.tar.gz")
	recoveryLoad := out.Join(ctx, "modules.load.recovery")
	var moduleOutputs android.WritablePaths
	for _, partition := range m.partitions(ctx) {
		var files android.Paths
		for _, source := range m.installList(ctx, partition) {
			name := filepath.Base(source)
			file := out.Join(ctx, "modules", partition, name)
			moduleOutputs = append(moduleOutputs, file)
			files = append(files, file)
		}
		ctx.SetOutputFiles(files, "."+partition)
	}
	rule := android.NewRuleBuilder(pctx, ctx).SandboxDisabled().Restat()
	rule.Command().Text("python3").Input(android.PathForModuleSrc(ctx, *m.properties.Script)).
		FlagWithArg("--profile ", profile).
		FlagWithArg("--system-dlkm ", fmt.Sprint(config.Bool("system_dlkm"))).
		FlagWithArg("--out ", out.String()).
		Implicits(inputs).
		ImplicitOutput(image).ImplicitOutputs(moduleOutputs).ImplicitOutput(configFile).
		ImplicitOutput(headers).ImplicitOutput(recoveryLoad)
	rule.Build("kleaf_outputs", "Kleaf kernel ("+profile+")")
	m.image = image
	m.installDir = android.PathForModuleInPartitionInstall(ctx, "")
	ctx.InstallFile(m.installDir, "kernel", image)
	ctx.SetOutputFiles(android.Paths{image}, "")
	ctx.SetOutputFiles(android.Paths{configFile}, ".config")
	ctx.SetOutputFiles(android.Paths{headers}, ".headers")
	ctx.SetOutputFiles(android.Paths{recoveryLoad}, ".recovery_load")
}

func (m *kernelModule) AndroidMkEntries() []android.AndroidMkEntries {
	if m.image == nil {
		return nil
	}
	return []android.AndroidMkEntries{{
		Class:      "ETC",
		OutputFile: android.OptionalPathForPath(m.image),
		ExtraEntries: []android.AndroidMkExtraEntriesFunc{
			func(ctx android.AndroidMkExtraEntriesContext, entries *android.AndroidMkEntries) {
				entries.SetString("LOCAL_MODULE_PATH", m.installDir.String())
				entries.SetString("LOCAL_INSTALLED_MODULE_STEM", "kernel")
			},
		},
	}}
}
