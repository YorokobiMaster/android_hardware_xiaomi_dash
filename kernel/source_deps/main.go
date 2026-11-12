// Copyright 2025 Google Inc. All rights reserved.
// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"flag"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"strings"
)

// Based on Soong's dir_to_depfile. Optional kernel sources and toolchains have
// dangling links; listing them as inputs makes Ninja rebuild on every invocation.
func main() {
	output := flag.String("o", "", "Output depfile")
	target := flag.String("t", "", "Target recorded in the depfile")
	flag.Parse()
	if *output == "" || *target == "" || flag.NArg() == 0 {
		log.Fatal("expected -o <depfile> -t <target> <directory>...")
	}
	var inputs []string
	for _, root := range flag.Args() {
		err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if entry.Type()&os.ModeSymlink != 0 {
				if _, err := os.Stat(path); os.IsNotExist(err) {
					return nil
				} else if err != nil {
					return err
				}
			}
			// Keep directory inputs so additions, removals and link replacements are tracked.
			inputs = append(inputs, path)
			return nil
		})
		if err != nil {
			log.Fatal(err)
		}
	}
	// Match Soong's makedeps escaping for Ninja's GCC-style depfile reader.
	escape := strings.NewReplacer(" ", "\\ ", ":", "\\:", "#", "\\#", "$", "$$", "\\", "\\\\")
	var depfile strings.Builder
	fmt.Fprintf(&depfile, "%s:", escape.Replace(*target))
	for _, input := range inputs {
		fmt.Fprintf(&depfile, " %s", escape.Replace(input))
	}
	depfile.WriteByte('\n')
	if err := os.WriteFile(*output, []byte(depfile.String()), 0666); err != nil {
		log.Fatal(err)
	}
}
