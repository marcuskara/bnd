package test.annotations.diff.payload;

import test.annotations.diff.*;

@Outer(value = {
	@Inner(value = {
			"1", "2"
	})
}, x = {
		1, 2, 3
})
public class ArrayAnnotationDiffTest {

}
