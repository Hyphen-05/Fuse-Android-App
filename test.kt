import java.util.concurrent.ConcurrentHashMap
class Outer {
    companion object {
        val map = ConcurrentHashMap<String, Outer.Inner>()
    }
    inner class Inner(val id: String)
    fun foo() {
        map["a"] = Inner("a")
    }
}
