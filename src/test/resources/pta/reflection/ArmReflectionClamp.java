// Integration fixture for arm② §11 step 3 (two-layer sound disposer) end-to-end.
//
// Dog and Cat are LOADED (via .class) but never ALLOCATED, so each <init> becomes
// reachable ONLY if the reflection arm adds a call edge to it. The newInstance()
// result is downcast to Animal. With an oracle proposing both "Dog" and "Cat",
// the downcast bound must admit Dog (a subtype) and clamp out Cat (not a subtype):
//   - Dog.<init> reachable, Cat.<init> NOT reachable.

public class ArmReflectionClamp {

    public static void main(String[] args) throws Exception {
        sink(Dog.class);   // load Dog without allocating it
        sink(Cat.class);   // load Cat without allocating it

        Class<?> c = Class.forName(name());
        Animal a = (Animal) c.newInstance();   // downcast bound = Animal
        a.toString();
    }

    static void sink(Object o) {
    }

    static String name() {
        StringBuilder s = new StringBuilder();
        s.append("D");
        s.append("og");
        return s.toString();
    }
}

class Animal {
}

class Dog extends Animal {
}

class Cat {
}
