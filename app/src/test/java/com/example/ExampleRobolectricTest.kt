package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.domain.model.AppScene
import com.example.domain.model.DeviceSceneState
import com.example.data.repository.AppPreferencesRepositoryImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Fuse", appName)
  }

  @Test
  fun testScenesPersistence() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    
    // 1. Create 3 scenes with various fields populated
    val scene1 = AppScene(
        id = "scene_1",
        name = "Cosmic Sky",
        targetScope = "ALL_DEVICES",
        state = DeviceSceneState(colorR = 255, colorG = 100, colorB = 50)
    )
    val scene2 = AppScene(
        id = "scene_2",
        name = "Aurora Borealis",
        targetScope = "SELECT_DEVICES",
        selectedDeviceMacs = listOf("AA:BB:CC:DD:EE:FF"),
        state = DeviceSceneState(modeIndex = 3, modeSpeed = 80)
    )
    val scene3 = AppScene(
        id = "scene_3",
        name = "Midnight Blue",
        targetScope = "ALL_DEVICES",
        state = DeviceSceneState(brightness = 40, isPowerOn = true)
    )
    
    val savedList = listOf(scene1, scene2, scene3)
    
    // 2. Save via AppPreferencesRepositoryImpl
    val repo = AppPreferencesRepositoryImpl(context)
    repo.saveScenes(savedList)
    
    // 3. Load via AppPreferencesRepositoryImpl representing a cold launch
    val loadedList = repo.loadScenes()
    
    // 4. Assert and print
    println("--- PERSISTENCE TEST OUTPUT ---")
    println("Actual loaded scene count: ${loadedList.size}")
    loadedList.forEachIndexed { index, scene ->
        println("Scene #${index + 1}: ID=${scene.id}, Name=${scene.name}, TargetScope=${scene.targetScope}, SelectedDeviceMacs=${scene.selectedDeviceMacs}, StateR=${scene.state.colorR}")
    }
    println("-------------------------------")
    
    assertEquals(3, loadedList.size)
    assertEquals("scene_1", loadedList[0].id)
    assertEquals("Cosmic Sky", loadedList[0].name)
    assertEquals("scene_2", loadedList[1].id)
    assertEquals("Aurora Borealis", loadedList[1].name)
    assertEquals(255, loadedList[0].state.colorR)
    assertEquals(3, loadedList[1].state.modeIndex)
  }
}
