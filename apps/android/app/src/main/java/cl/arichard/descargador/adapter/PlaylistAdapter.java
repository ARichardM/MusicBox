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
 /** Playlist recién creada (p. ej. importada): entra con un rebote la próxima vez que se dibuja. */
 private String arriving="";
 /** Marca [name] para que aparezca con rebote y devuelve su posición en la lista, o -1 si no se ve. */
 public int highlightArrival(String name){
  arriving=name==null?"":name;
  int index=displayed.indexOf(arriving);
  if(index>=0)notifyItemChanged(index);
  return index;
 }
 private void playArrival(View row){
  float density=context.getResources().getDisplayMetrics().density;
  row.animate().cancel();
  row.setAlpha(0f);row.setScaleX(.78f);row.setScaleY(.78f);row.setTranslationY(40*density);
  row.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(560)
    .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
  // Dos destellos suaves en el azul de MusicBox, como las canciones recién agregadas.
  android.graphics.drawable.GradientDrawable glow=new android.graphics.drawable.GradientDrawable();
  glow.setColor(androidx.core.content.ContextCompat.getColor(context,R.color.accent));
  glow.setCornerRadius(16*density);glow.setAlpha(0);row.setForeground(glow);
  android.animation.ValueAnimator pulse=android.animation.ValueAnimator.ofFloat(0f,1f);
  pulse.setStartDelay(260);pulse.setDuration(1500);
  pulse.addUpdateListener(a->{float t=(float)a.getAnimatedValue();glow.setAlpha((int)Math.round(70*Math.abs(Math.sin(t*Math.PI*2))*(1f-t)));});
  pulse.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator animation){row.setForeground(null);}});
  pulse.start();
 }
 @Override public void onViewRecycled(@NonNull Holder h){
  h.itemView.animate().cancel();h.itemView.setAlpha(1f);h.itemView.setScaleX(1f);h.itemView.setScaleY(1f);
  h.itemView.setTranslationY(0f);h.itemView.setForeground(null);
  super.onViewRecycled(h);
 }
 @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){return new Holder(LayoutInflater.from(context).inflate(R.layout.item_playlist,p,false));}
 @Override public void onBindViewHolder(@NonNull Holder h,int position){
  String name=displayed.get(position);PlaylistManager pm=PlaylistManager.getInstance(context);
  List<cl.arichard.descargador.model.Song> songs=pm.resolveSongs(name,library);
  h.name.setText(cl.arichard.descargador.util.Tr.name(name));h.collage.setSongs(songs);
  int plays = cl.arichard.descargador.util.Clicks.of(context, songs);
  String countText = songs.size() + (songs.size()==1?cl.arichard.descargador.util.Tr.t(" canción"):cl.arichard.descargador.util.Tr.t(" canciones"));
  if (plays > 0) {
   android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(countText).append("  ·  ");
   int start = label.length(); label.append("▶");
   android.graphics.drawable.Drawable play = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_play_arrow);
   if (play != null) {
    int size = Math.round(12 * context.getResources().getDisplayMetrics().density);
    play.setBounds(0, 0, size, size); androidx.core.graphics.drawable.DrawableCompat.setTint(play,
      androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary));
    label.setSpan(new android.text.style.ImageSpan(play, android.text.style.ImageSpan.ALIGN_CENTER), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
   }
   label.append("  ").append(cl.arichard.descargador.util.Clicks.format(plays)); h.count.setText(label);
  } else h.count.setText(countText);
  String description=pm.getDescription(name);h.description.setText(description);h.description.setVisibility(description.isEmpty()?View.GONE:View.VISIBLE);
  h.itemView.setOnClickListener(v->{if(listener!=null)listener.onPlaylistClick(name);});
  h.more.setOnClickListener(v->{if(listener!=null)listener.onDeleteClick(name,v);});
  cl.arichard.descargador.util.SafeTap.install(h.more);cl.arichard.descargador.util.SafeTap.installRowTap(h.itemView);
  if(!arriving.isEmpty()&&arriving.equals(name)){arriving="";playArrival(h.itemView);}
 }
 @Override public int getItemCount(){return displayed.size();}
 static class Holder extends RecyclerView.ViewHolder{
  final TextView name,count,description;final ImageView more;final cl.arichard.descargador.util.CoverCollageView collage;
  Holder(View v){super(v);name=v.findViewById(R.id.txtPlaylistName);count=v.findViewById(R.id.txtPlaylistCount);description=v.findViewById(R.id.txtPlaylistDescription);more=v.findViewById(R.id.btnPlaylistMore);collage=v.findViewById(R.id.playlistCollage);}
 }
}
