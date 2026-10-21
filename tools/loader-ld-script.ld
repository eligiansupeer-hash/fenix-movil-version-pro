/* ---------------------------------------------------------------------------
 * Script de enlazador para el loader interno de proot con LLD (NDK r29).
 *
 * El makefile upstream enlaza el loader con -Ttext=0x2000000000 (LOADER_ADDRESS
 * de arch.h para aarch64). Con el script por defecto de LLD, la congruencia
 * offset≡vaddr de los segmentos produce un ELF esparcido de ~128 GB, inútil
 * (GNU ld historicamente mantiene offsets compactos). Este script minimalista
 * conserva la dirección virtual fija que proot espera pero con offsets de
 * archivo compactos (~3 KB), y descarta secciones que el loader no usa.
 *
 * Uso: ver tools/build-proot.sh (LOADER_LDFLAGS con -Wl,-T,este-archivo).
 * ------------------------------------------------------------------------- */
OUTPUT_FORMAT("elf64-littleaarch64")
OUTPUT_ARCH(aarch64)
ENTRY(_start)
PHDRS { text PT_LOAD FILEHDR PHDRS; }
SECTIONS
{
  . = 0x2000000000 + SIZEOF_HEADERS;
  .text           : { *(.text .text.*) } :text
  .rodata         : { *(.rodata .rodata.*) } :text
  .data           : { *(.data .data.*) *(.got*) } :text
  .bss            : { *(.bss .bss.*) *(COMMON) } :text
  /DISCARD/       : { *(.comment*) *(.note*) *(.eh_frame*) }
}
