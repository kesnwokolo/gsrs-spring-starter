package ix.utils;

import java.lang.*;
import java.lang.ref.SoftReference;

public class LiteralReference<T>{
	
	private SoftReference<T> sr;
	private int hashcode;

	public LiteralReference(T t){
		this.sr=new SoftReference<T>(t);
		this.hashcode=System.identityHashCode(o);
	}
	
	public T get(){
		return sr.get();
	}
	@Override
	public int hashCode(){
		return this.hashcode;
	}
	@Override
	public boolean equals(Object oref){
		if(oref==null)return false;
		if(oref instanceof LiteralReference){
			LiteralReference<?> or=(LiteralReference<?>)oref;
			return (this.get() == or.get());
		}
		return false;
	}
	public static <T> LiteralReference<T> of(T t) {
		return new LiteralReference<T>(t);
	}
	
	public String toString(){
		return "Ref to:" + sr.get().toString();
	}
}
