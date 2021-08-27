package edu.anonymous.model;

public class LocalModel {

    public String name;

    public int number;

    @Override
    public boolean equals(Object other){
        if(other instanceof LocalModel){
            LocalModel localModel = (LocalModel) other;
            return localModel.name.equals(this.name)
                    && localModel.number == this.number;
        }
        return false;
    }

    public boolean isSameByNameNotNum(LocalModel other){
        return other.name.equals(this.name)
                && other.number != this.number;
    }
}
