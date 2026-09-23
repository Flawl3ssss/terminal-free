package com.redtermapp.distro

object DistroRegistry {
    private const val RELEASE_BASE = "https://github.com/Flawl3ssss/terminal-free/releases/download/rootfs-v1"

    val allDistros: List<Distro> = listOf(
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu 26.04 LTS",
            description = "Isolated Ubuntu under PRoot. No access to phone storage.",
            baseUrl = "$RELEASE_BASE/ubuntu-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64"),
            installSizeMb = 350,
            packageManager = "apt"
        )
    )

    fun forDevice(abi: String): List<Distro> {
        val mapped = abiToProotArch(abi)
        return allDistros.filter { it.prootArchs.any { arch -> arch == mapped } }
    }
}
