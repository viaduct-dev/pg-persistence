import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TestClasspathTest {
    @ParameterizedTest
    @ValueSource(strings = ["kotlin/Unit.class", "kotlin/reflect/full/KClasses.class", "kotlinx/coroutines/Job.class"])
    fun `project and test libraries share one resolved dependency graph`(resource: String) {
        assertThat(javaClass.classLoader.getResources(resource).toList()).hasSize(1)
    }
}
