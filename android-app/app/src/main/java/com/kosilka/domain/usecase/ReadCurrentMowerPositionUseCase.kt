package com.kosilka.domain.usecase

import com.kosilka.data.device.MowerDevice
import com.kosilka.domain.model.Point2dMm
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadCurrentMowerPositionUseCase @Inject constructor(
    private val mowerDevice: MowerDevice
) {
    suspend fun read(): Result<Point2dMm> = mowerDevice.readCurrentPosition()
}
