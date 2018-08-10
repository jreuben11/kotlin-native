/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef SRC_LTOEXT_H
#define SRC_LTOEXT_H

#include <llvm-c/Core.h>
#include <llvm-c/TargetMachine.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  OUTPUT_KIND_OBJECT_FILE,
  OUTPUT_KIND_BITCODE
} OutputKind;

typedef struct {
  int optLevel;
  int sizeLevel;
  int shouldProfile;
  const char *fileName;
  OutputKind outputKind;
  const char *targetTriple;
  LLVMRelocMode relocMode;
  int shouldPerformLto;
} CompilationConfiguration;

int LLVMLtoCodegen(LLVMContextRef contextRef,
                   LLVMModuleRef programModuleRef,
                   LLVMModuleRef runtimeModuleRef,
                   LLVMModuleRef stdlibModuleRef,
                   CompilationConfiguration compilationConfiguration);

#ifdef __cplusplus
}
#endif

#endif //SRC_LTOEXT_H
