package cl.arichard.descargador.adapter;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.data.PlaylistManager;
import java.util.ArrayList;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.Holder> {
 public interface OnPlaylistClickListener { void onPlaylistClick(String name); void onDeleteClick(String name, View anchor); void onAutoDownloadChanged(String name, boolean enabled); }
 private final Context context; private final OnPlaylistClickListener listener;
 private final List<cl.arichard.descargador.model.Song> library=new ArrayList<>();
 private final List<String> original=new ArrayList<>(), displayed=new ArrayList<>();
 public PlaylistAdapter(Context c,List<String> names,OnPlaylistClickListener l){context=c;listener=l;updatePlaylists(names);}
 public void setLibrary(List<cl.arichard.descargador.model.Song> songs){library.clear();library.addAll(songs);notifyDataSetChanged();}
 public void updatePlaylists(List<String> names){original.clear();if(names!=null)original.addAll(names);filter("");}
 public void filter(String query){displayed.clear();String q=query==null?"":query.trim().toLowerCase(java.util.Locale.ROOT);for(String n:original)if(q.isEmpty()||n.toLowerCase(java.util.Locale.ROOT).contains(q))displayed.add(n);notifyDataSetChanged();}
 public int getDisplayedCount(){return displayed.size();}
 @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){return new Holder(LayoutInflater.from(context).inflate(R.layout.item_playlist,p,false));}
 @Override public void onBindViewHolder(@NonNull Holder h,int position){
  String name=displayed.get(position);PlaylistManager pm=PlaylistManager.getInstance(context);
  List<cl.arichard.descargador.model.Song> songs=pm.resolveSongs(name,library);
  h.name.setText(cl.arichard.descargador.util.Tr.name(name));h.collage.setSongs(songs);h.count.setText(songs.size()+(songs.size()==1?cl.arichard.descargador.util.Tr.t(" canción"):cl.arichard.descargador.util.Tr.t(" canciones"))+cl.arichard.descargador.util.Clicks.suffix(cl.arichard.descargador.util.Clicks.of(context,songs)));
  String description=pm.getDescription(name);h.description.setText(description);h.description.setVisibility(description.isEmpty()?View.GONE:View.VISIBLE);
  h.itemView.setOnClickListener(v->{if(listener!=null)listener.onPlaylistClick(name);});
  h.more.setOnClickListener(v->{if(listener!=null)listener.onDeleteClick(name,v);});
  cl.arichard.descargador.util.SafeTap.install(h.more);cl.arichard.descargador.util.SafeTap.installRowTap(h.itemView);
 }
 @Override public int getItemCount(){return displayed.size();}
 static class Holder extends RecyclerView.ViewHolder{
  final TextView name,count,description;final ImageView more;final cl.arichard.descargador.util.CoverCollageView collage;
  Holder(View v){super(v);name=v.findViewById(R.id.txtPlaylistName);count=v.findViewById(R.id.txtPlaylistCount);description=v.findViewById(R.id.txtPlaylistDescription);more=v.findViewById(R.id.btnPlaylistMore);collage=v.findViewById(R.id.playlistCollage);}
 }
}
