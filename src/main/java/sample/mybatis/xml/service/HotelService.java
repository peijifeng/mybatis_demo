package sample.mybatis.xml.service;


import sample.mybatis.xml.domain.Hotel;

public interface HotelService {
    Hotel selectByCityId(int cityId);
}
