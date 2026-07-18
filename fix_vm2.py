with open('app/src/main/java/com/example/RgbControllerViewModel.kt', 'r') as f:
    content = f.read()

content = content.replace('import dagger.hilt.android.lifecycle.HiltViewModel\n', '')
content = content.replace('import javax.inject.Inject\n', '')
content = content.replace('import dagger.hilt.android.qualifiers.ApplicationContext\n', '')
content = content.replace('@dagger.hilt.android.lifecycle.HiltViewModel', '')
content = content.replace('class RgbControllerViewModel @javax.inject.Inject constructor(', 'class RgbControllerViewModel(')
content = content.replace('@dagger.hilt.android.qualifiers.ApplicationContext private val application: android.content.Context,', 'private val application: android.content.Context,')

with open('app/src/main/java/com/example/RgbControllerViewModel.kt', 'w') as f:
    f.write(content)
