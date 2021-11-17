package sample.mybatis.xml.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sample.mybatis.xml.domain.Hotel;
import sample.mybatis.xml.mapper.HotelMapper;

@Service
public class HotelServiceImpl implements HotelService {

    @Autowired
    private HotelMapper hotelMapper;

    @Override
    public Hotel selectByCityId(int cityId) {
        return hotelMapper.selectHotelByCityId(cityId);
    }
}
