package ceui.lisa.utils;

import ceui.lisa.interfaces.ListShow;

public class Empty {

    public static boolean stringEmpty(String input){
        if(input == null){
            return true;
        }
        return input.length() == 0;
    }


    public static void main(String[] args) {
        System.out.print(Empty.stringEmpty("123456"));
    }


    public static <T extends ListShow> int objectEmpty(T e){
        if(e == null){
            return -1;
        }
        if(e.getList() == null){
            return -2;
        }
        if(e.getList().size() == 0){
            return -3;
        }else {
            return 1;
        }
    }
}
